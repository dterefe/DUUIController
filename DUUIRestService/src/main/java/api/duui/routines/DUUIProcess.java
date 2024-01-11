package api.duui.routines;

import api.Application;
import api.duui.DUUIState;
import api.duui.DUUIStatus;
import api.duui.document.DUUIDocumentInput;
import api.duui.document.DUUIDocumentOutput;
import api.duui.pipeline.DUUIPipelineController;
import api.duui.routines.process.DUUIProcessController;
import api.storage.DUUIMongoDBStorage;
import de.tudarmstadt.ukp.dkpro.core.api.metadata.type.DocumentMetaData;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.bson.Document;
import org.dkpro.core.io.xmi.XmiWriter;
import org.texttechnologylab.DockerUnifiedUIMAInterface.DUUIComposer;
import org.texttechnologylab.DockerUnifiedUIMAInterface.data_reader.DUUIDocument;
import org.texttechnologylab.DockerUnifiedUIMAInterface.data_reader.IDUUIDataReader;
import org.texttechnologylab.DockerUnifiedUIMAInterface.driver.DUUIUIMADriver;
import org.texttechnologylab.DockerUnifiedUIMAInterface.io.AsyncCollectionReader;
import org.texttechnologylab.DockerUnifiedUIMAInterface.lua.DUUILuaContext;
import org.texttechnologylab.DockerUnifiedUIMAInterface.pipeline_storage.mongodb.DUUIMongoStorageBackend;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static api.duui.routines.process.DUUIProcessService.*;
import static org.texttechnologylab.DockerUnifiedUIMAInterface.io.AsyncCollectionReader.getFilesInDirectoryRecursive;

public class DUUIProcess extends Thread {

    private ScheduledFuture<?> updater;
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final DUUIComposer composer;
    private Document process;
    private Document pipeline;
    private Document settings;
    private String userId;
    private DUUIUIMADriver.Component xmiWriter;
    private String processId;
    private String pipelineId;
    private boolean failed = false;
    private DUUIState state = DUUIState.INACTIVE;
    private boolean includesUIMADriver = false;
    private boolean interrupted = false;
    private boolean keepAlive;
    private int threadCount = 0;
    String outputFolder;
    String writerPath;
    IDUUIDataReader outputWriter;

    @Override
    public void run() {
        while (!interrupted()) {
            try {
                lock.lock();
                if (state == DUUIState.IDLE) {
                    condition.await();
                } else {
                    state = DUUIState.ACTIVE;
                    execute();
                }
            } catch (InterruptedException e) {
                cancel();
            } catch (Exception e) {
                onException(e);
            } finally {
                lock.unlock();
            }
        }
    }

    public DUUIProcess(Document process, Document pipeline, boolean keepAlive) throws Exception {
        this.process = process;
        this.pipeline = pipeline;
        this.keepAlive = keepAlive;

        processId = process.getString("oid");
        pipelineId = process.getString("pipeline_id");

        composer = new DUUIComposer()
            .withSkipVerification(true)
            .withDebug(true)
            .asService(true)
            .withStorageBackend(
                new DUUIMongoStorageBackend(DUUIMongoDBStorage.getConnectionURI()))
            .withLuaContext(new DUUILuaContext().withJsonLibrary());

        if (keepAlive) {
            instantiatePipeline();
        }
    }

    private void instantiatePipeline() throws Exception {
        state = DUUIState.SETUP;
        includesUIMADriver = setupDrivers(composer, pipeline);
        setupComponents(composer, pipeline);
        composer.instantiate_pipeline();
        state = DUUIState.IDLE;
    }

    private void startPipeline(Document process, Document settings) {
        this.process = process;
        this.pipeline = DUUIPipelineController.getPipelineById(process.getString("pipeline_id"));
        this.userId = pipeline.getString("user_id");

        processId = process.getString("oid");
        pipelineId = process.getString("pipeline_id");

        startUpdater();
        if (state == DUUIState.INACTIVE) {
            try {
                instantiatePipeline();
            } catch (Exception error) {
                onException(error);
            }
        } else if (state == DUUIState.IDLE) {
            composer.resetService();
        } else {
            onException(new Exception("Pipeline is in an invalid state: %s.".formatted(state)));
        }

        this.process = process;
        this.settings = settings;
        this.pipeline = DUUIPipelineController
            .getPipelineById(process.getString("pipeline_id"));

        state = DUUIState.ACTIVE;

        try {
            lock.lock();
            condition.signal();
        } finally {
            lock.unlock();
        }
    }

    private void execute() throws Exception {
        Application.metrics.get("active_processes").incrementAndGet();
        DUUIProcessController.setStatus(processId, DUUIStatus.INPUT);

        DUUIDocumentInput input = new DUUIDocumentInput(process.get("input", Document.class));
        DUUIDocumentOutput output = new DUUIDocumentOutput(process.get("output", Document.class));

        outputFolder = output.getFolder();
        writerPath = Paths.get("temp/duui/%s".formatted(processId), outputFolder).toString();

        toggleXmiWriter(output);

        if (input.isText()) executeText(input, output);
        else executeCloud(input, output);

        DUUIProcessController.setInstantiationDuration(processId, composer.getInstantiationDuration());
        if (output.isCloudProvider() && outputWriter != null && !failed && !interrupted) {
            DUUIProcessController.setStatus(processId, DUUIStatus.OUTPUT);

            try {
                List<DUUIDocument> documents = getFilesInDirectoryRecursive(writerPath);
                outputWriter.writeFiles(documents, output.getFolder());
            } catch (IOException e) {
                onException(e);
            }
        }

        if (!failed && !interrupted) {
            DUUIProcessController.setStatus(processId, DUUIStatus.COMPLETED);
            Application.metrics.get("completed_processes").incrementAndGet();
        }

        if (deleteTempOutputDirectory(new File(Paths.get(writerPath).toString()))) {
            File path = new File(Paths.get("temp/duui/%s".formatted(processId)).toString());
            if (path.delete()) {
                composer.addStatus("Clean up complete");
            }
        }
    }

    private void executeText(DUUIDocumentInput input, DUUIDocumentOutput output) throws Exception {
        outputWriter = getDataReaderFromString(output.getTarget(), userId);

        DUUIProcessController.setDocumentNames(processId, Set.of("Text"));
        DUUIProcessController.updateDocuments(
            processId, Set.of(
                new DUUIDocument(
                    "Text",
                    "",
                    input.getContent().getBytes(StandardCharsets.UTF_8)
                )
            )
        );

        JCas cas = JCasFactory.createText(input.getContent());

        if (JCasUtil.select(cas, DocumentMetaData.class).isEmpty()) {
            DocumentMetaData dmd = DocumentMetaData.create(cas);
            dmd.setDocumentId(pipeline.getString("name"));
            dmd.setDocumentTitle(pipeline.getString("name"));
            dmd.setDocumentUri(pipeline.getString("name") + "_" + process.getLong("startedAt"));
            dmd.addToIndexes();
        }

        composer.addStatus("Loaded document, starting Pipeline");
        DUUIProcessController.setStatus(processId, DUUIStatus.RUNNING);

        composer.run(cas, pipeline.getString("name") + "_" + process.getLong("startedAt"));


    }

    private void executeCloud(DUUIDocumentInput input, DUUIDocumentOutput output) {
        IDUUIDataReader inputeader = getDataReaderFromString(input.getSource(), userId);
        outputWriter = input.sameAs(output) ? inputeader : getDataReaderFromString(output.getTarget(), userId);

        AsyncCollectionReader collectionReader;
        try {
            collectionReader = new AsyncCollectionReader.Builder()
                .withSourceDirectory(input.getFolder())
                .withSourceFileExtension(input.getFileExtension())
                .withInputDataReader(inputeader)
                .withTargetDirectory(output.getFolder())
                .withTargetFileExtension(output.getFileExtension())
                .withOutputDataReader(outputWriter)
                .withAddMetadata(settings.getBoolean("addMetaData", true))
                .withSortBySize(settings.getBoolean("sortBySize", false))
                .withLanguage(settings.getString("language"))
                .withSkipSmallerFiles((int) settings.getOrDefault("skipSmallerFiles", 0))
                .withCheckTarget(settings.getBoolean("checkTarget", false))
                .withRecursive(settings.getBoolean("recursive", false))
                .build(composer);
        } catch (RuntimeException e) {
            onException(new IOException(String.format("Source path %s was not found", input.getFolder())));
            return;
        }

        DUUIProcessController.setDocumentNames(processId, collectionReader.getDocumentNames());

        threadCount = Math.min(5, collectionReader.getDocumentCount());
        composer.withWorkers(threadCount);

        Application.metrics.get("active_threads").getAndAdd(threadCount);
        DUUIProcessController.setStatus(processId, DUUIStatus.RUNNING);
        composer.addStatus(
            "AsyncCollectionReader",
            "Loaded " + collectionReader.getDocumentCount() + " documents");

        try {
            composer.run(
                collectionReader,
                pipeline.getString("name") + "_" + process.getLong("startedAt"));

        } catch (Exception e) {
            onException(e);
        }
    }

    private void update() {
        if (composer == null) return;

        try {
            DUUIProcessController.setProgress(processId, composer.getProgress());
            DUUIProcessController.updateTimeline(processId, composer.getLog());
            DUUIProcessController.updateDocuments(processId, composer.getDocuments());
        } catch (Exception error) {
            onException(error);
        }
    }

    private void startUpdater() {
        updater = Executors
            .newScheduledThreadPool(1)
            .scheduleAtFixedRate(this::update, 0, 2, TimeUnit.SECONDS);
    }

    private void onException(Exception error) {
        failed = true;

        DUUIProcessController.setError(
            processId,
            String.format("%s - %s", error.getClass(), error.getMessage()));

        if (composer != null) {
            DUUIProcessController.setProgress(processId, composer.getProgress());
            composer.getDocuments().stream().filter(document ->
                !document.getIsFinished() ||
                    DUUIStatus.oneOf(document.getStatus(),
                        DUUIStatus.RUNNING,
                        DUUIStatus.WAITING
                    )
            ).forEach(document -> {
                document.setStatus(DUUIStatus.FAILED);
                document.setError("Process failed before Document was fully processed.");
                document.setFinished(true);
                document.setProcessingEndTime();
            });

            DUUIProcessController.updateDocuments(processId, composer.getDocuments());
        }

        DUUIProcessController.setStatus(processId, DUUIStatus.FAILED);
        exit();
    }

    private void cancel() {
        interrupted = true;
        keepAlive = false;

        Application.metrics.get("cancelled_processes").incrementAndGet();
        if (state == DUUIState.ACTIVE) onShutdown();
    }

    private void onShutdown() {
        state = keepAlive ? DUUIState.IDLE : DUUIState.SHUTDOWN;

        DUUIProcessController.setStatus(processId, DUUIStatus.SHUTDOWN);
        composer.asService(keepAlive).setShutdownAtomic(true);
        try {
            composer.shutdown();
        } catch (UnknownHostException e) {
            onException(e);
        }

        exit();
    }

    private void exit() {
        DUUIProcessController.setFinished(processId, true);
        DUUIProcessController.setFinishTime(processId);

        Application.metrics.get("active_processes").decrementAndGet();
        Application.metrics.get("active_threads").getAndAdd(-threadCount);
        threadCount = 0;

        if (!keepAlive) {
            DUUIPipelineController.setServiceStartTime(pipelineId, 0);
            DUUIPipelineController.getServices().remove(pipelineId);
        }

        DUUIProcessController.removeProcess(processId);

        try {
            lock.lock();
            state = DUUIState.IDLE;
            DUUIProcessController.updateDocuments(processId, composer.getDocuments());
            DUUIProcessController.updateTimeline(processId, composer.getLog());
            DUUIProcessController.setProgress(processId, composer.getProgress());
            DUUIProcessController.removeProcess(processId);
            if (interrupted) {
                keepAlive = false;
                onShutdown();
            }
        } finally {
            lock.unlock();
        }

        while (!composer.processingFinished()) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (updater != null) {
            updater.cancel(true);
        }

        if (settings.getBoolean("notify", false)) {
            System.out.println("EMAIL NOTIFICATION NOT IMPLEMENTED");
            // sendNotificationEmail(_userEmail)
        }

        if (!keepAlive && isAlive()) {
            interrupt();
        }
    }

    public void onApplicationShutdown() {
        while (state == DUUIState.SETUP) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                onApplicationShutdown();
            }
        }

        if (state == DUUIState.ACTIVE) cancel();

        DUUIPipelineController.removeService(pipelineId);
        DUUIPipelineController.setServiceStartTime(pipelineId, 0L);

        try {
            composer.asService(false).shutdown();
        } catch (UnknownHostException error) {
            onException(error);
        }
    }

    private void toggleXmiWriter(DUUIDocumentOutput output) throws IOException, SAXException {
        if (output.isCloudProvider() && xmiWriter == null) {
            try {
                if (!includesUIMADriver) {
                    composer.addDriver(new DUUIUIMADriver());
                }
                xmiWriter = getXmiWriter(
                    writerPath,
                    output.getFileExtension())
                    .withName("XMIWriter");
                composer.add(xmiWriter);
            } catch (Exception e) {
                onException(e);
            }
        } else if (!output.isCloudProvider() && xmiWriter != null) {
            composer.getPipeline().remove(composer.getPipeline().lastElement());
            xmiWriter = null;
        } else if (output.isCloudProvider() && xmiWriter != null) {

            xmiWriter.setAnalysisEngineParameter(XmiWriter.PARAM_TARGET_LOCATION, writerPath);
            xmiWriter.setAnalysisEngineParameter(XmiWriter.PARAM_FILENAME_EXTENSION, output.getFileExtension());
        }
    }
}
