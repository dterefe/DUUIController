package org.texttechnologylab.duui.api.metrics;

import org.texttechnologylab.duui.api.metrics.providers.*;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.IOException;
import java.io.StringWriter;


public class DUUIMetricsManager {

    public static void init() {
        DUUIHTTPMetrics.register();
        DUUIProcessMetrics.register();
        DUUISystemMetrics.register();
        DUUIStorageMetrics.register();
    }

    public static String export() throws IOException {
        StringWriter writer = new StringWriter();
        CollectorRegistry registry = CollectorRegistry.defaultRegistry;

        TextFormat.write004(writer, registry.metricFamilySamples());
        return writer.toString();
    }
}
