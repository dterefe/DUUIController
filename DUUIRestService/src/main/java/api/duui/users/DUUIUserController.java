package api.duui.users;

import api.requests.validation.UserValidator;
import api.storage.DUUIMongoDBStorage;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.texttechnologylab.DockerUnifiedUIMAInterface.data_reader.DUUIMinioDataReader;
import spark.Request;
import spark.Response;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static api.requests.validation.UserValidator.*;
import static api.requests.validation.Validator.*;
import static api.storage.DUUIMongoDBStorage.combineUpdates;
import static api.storage.DUUIMongoDBStorage.mapObjectIdToString;


public class DUUIUserController {

    private static final List<String> _fields = List.of(
        "preferences",
        "session",
        "key",
        "dropbox",
        "minio"
    );


    public static Document getDropboxCredentials(Document user) {
        Document projection = DUUIMongoDBStorage
            .Users()
            .find(Filters.eq(user.getObjectId("_id")))
            .projection(Projections.include("dropbox"))
            .first();

        if (isNullOrEmpty(projection)) {
            return new Document();
        }

        return projection.get("dropbox", Document.class);

    }

    public static Document getMinioCredentials(Document user) {
        Document projection = DUUIMongoDBStorage
            .Users()
            .find(Filters.eq(user.getObjectId("_id")))
            .projection(Projections.include("minio"))
            .first();

        if (isNullOrEmpty(projection)) {
            return new Document();
        }

        return projection.get("minio", Document.class);
    }

    public static Document getUserById(ObjectId id) {
        return DUUIMongoDBStorage
            .Users()
            .find(Filters.eq(id))
            .first();
    }

    public static Document getUserById(String id) {
        return DUUIMongoDBStorage
            .Users()
            .find(Filters.eq(new ObjectId(id)))
            .first();
    }

    public static Document getUserByEmail(String email) {
        return DUUIMongoDBStorage
            .Users()
            .find(Filters.eq("email", email))
            .first();
    }

    public static Document getUserByAuthorization(String authorization) {
        return DUUIMongoDBStorage
            .Users()
            .find(Filters.eq("key", authorization))
            .projection(Projections.exclude("password"))
            .first();
    }

    public static Document getUserBySession(String session) {
        return DUUIMongoDBStorage
            .Users()
            .find(Filters.eq("session", session))
            .projection(Projections.exclude("password"))
            .first();
    }

    public static Document getUserByResetToken(String token) {
        return DUUIMongoDBStorage
            .Users()
            .find(Filters.eq("password_reset_token", token))
            .projection(Projections.include("_id", "email", "reset_token_expiration"))
            .first();
    }

    public static String findOneById(Request request, Response response) {
        Document user = getUserById(request.params(":id"));
        if (user == null)
            return userNotFound(response);

        mapObjectIdToString(user);
        response.status(200);
        return user.toJson();
    }

    public static String findOneByEmail(Request request, Response response) {
        Document user = getUserByEmail(request.params(":email"));

        if (isNullOrEmpty(user)) {
            return userNotFound(response);
        }

        mapObjectIdToString(user);

        response.status(200);
        return user.toJson();
    }

    public static String findOneByAuthorization(Request request, Response response) {
        String authorization = request.params(":key");
        Document user = authenticate(authorization);

        if (isNullOrEmpty(user))
            return userNotFound(response);

        user.put("connections", DUUIUserController.getConnectionsForUser(user));
        mapObjectIdToString(user);

        response.status(200);
        return user.toJson();
    }

    public static String insertOne(Request request, Response response) {
        Document body = Document.parse(request.body());

        String email = body.getString("email");
        if (email.isEmpty())
            return missingField(response, "email");

        String password = body.getString("password");
        if (password.isEmpty())
            return missingField(response, "password");
        String role = body.getString("role");

        Document newUser = new Document("email", email)
            .append("password", password)
            .append("createdAt", new Date().toInstant().toEpochMilli())
            .append("role", role.isEmpty() ? "user" : role)
            .append("preferences", new Document("tutorial", true).append("language", "english").append("notifications", false))
            .append("session", body.getOrDefault("session", null))
            .append("authorization", null)
            .append("dbx_access_token", null)
            .append("dbx_refresh_token", null)
            .append("minio_access_key", null)
            .append("minio_secret_key", null)
            .append("minio_endpoint", null);

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .insertOne(newUser);

        mapObjectIdToString(newUser);
        response.status(200);
        return newUser.toJson();
    }

    public static String deleteOne(Request request, Response response) {
        String authorization = request.headers("authorization");

        Document user = authenticate(authorization);
        if (isNullOrEmpty(user)) return unauthorized(response);

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .deleteOne(Filters.eq(new ObjectId(request.params(":id"))));

        response.status(201);
        return new Document("message", "Successfully deleted").toJson();
    }

    public static String updateApiKey(Request request, Response response) {
        String authorization = request.headers("authorization");

        Document user = authenticate(authorization);
        if (isNullOrEmpty(user)) return unauthorized(response);

        Document body = Document.parse(request.body());

        String key = body.getString("key");
        if (isNullOrEmpty(key))
            return missingField(response, "email");

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .findOneAndUpdate(
                Filters.eq(user.getObjectId("_id")),
                Updates.set("key", key));

        return updateSuccess(response, "key");
    }

    public static String updateEmail(Request request, Response response) {
        String authorization = request.headers("authorization");

        Document user = authenticate(authorization);
        if (isNullOrEmpty(user)) return unauthorized(response);

        Document body = Document.parse(request.body());

        String email = body.getString("email");
        if (email.isEmpty())
            return missingField(response, "email");

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .findOneAndUpdate(
                Filters.eq("_id", new ObjectId(request.params(":id"))),
                Updates.set("email", email));

        return updateSuccess(response, "email");
    }

    public static String updateSession(Request request, Response response) {
        Document body = Document.parse(request.body());

        String email = body.getString("email");
        if (email.isEmpty())
            return missingField(response, "email");

        String session = body.getString("session");
        if (session.isEmpty())
            return missingField(response, "session");

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .findOneAndUpdate(
                Filters.eq("email", email),
                Updates.set("session", session));

        return new Document("email", email).append("session", session).toJson();
    }

    public static String recoverPassword(Request request, Response response) {
        Document body = Document.parse(request.body());

        String email = body.getString("email");
        if (isNullOrEmpty(email))
            return missingField(response, "email");

        String passwordResetToken = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + 60 * 30 * 1000; // 30 Minutes

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .findOneAndUpdate(Filters.eq("email", email),
                Updates.combine(
                    Updates.set("password_reset_token", passwordResetToken),
                    Updates.set("reset_token_expiration", expiresAt)));

        sendPasswordResetEmail(email, passwordResetToken);
        response.status(200);
        return new Document("message", "Email has been sent.").toJson();
    }

    private static void sendPasswordResetEmail(String email, String passwordResetToken) {

    }

    public static String resetPassword(Request request, Response response) {
        Document body = Document.parse(request.body());

        String password = body.getString("password");
        if (password.isEmpty())
            return missingField(response, password);

        String token = body.getString("password_reset_token");
        if (token.isEmpty())
            return unauthorized(response);

        Document user = getUserByResetToken(token);
        if (user.isEmpty())
            return userNotFound(response);

        long expiresAt = user.getLong("reset_token_expiration");
        if (expiresAt > System.currentTimeMillis())
            return expired(response);

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .findOneAndUpdate(
                Filters.eq(user.getObjectId("_id")),
                Updates.combine(
                    Updates.set("password", password),
                    Updates.set("password_reset_token", null),
                    Updates.set("reset_token_expiration", null)));

        return new Document("message", "Password has been updated")
            .append("email", user.getString("email")).toJson();
    }

    public static boolean validateSession(String id, String session) {
        Document user = getUserById(id);
        return user != null && user.getString("session").equals(session);
    }

    public static String updateUser(Request request, Response response) {
        String id = request.params(":id");
        Document update = Document.parse(request.body());

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .findOneAndUpdate(
                Filters.eq(new ObjectId(id)),
                combineUpdates(update, _fields)
            );


        response.status(200);
        Document user = getUserById(id);
        return user.toJson();
    }

    public static String dbxIsAuthorized(Request request, Response response) {
        String session = request.headers("session");
        if (!UserValidator.isAuthorized(session, Role.USER))
            return unauthorized(response);

        Document user = getUserById(request.params(":id"));
        if (user == null)
            return userNotFound(response);

        if (!isDropboxConnected(user)) {
            return missingAuthorization(response, "Dropbox");
        }

        mapObjectIdToString(user);
        response.status(200);
        return user.toJson();
    }

    private static boolean isDropboxConnected(Document user) {
        Document credentials = DUUIUserController.getDropboxCredentials(user);
        if (isNullOrEmpty(credentials)) return false;
        return credentials.getString("refresh_token") != null;
    }

    private static boolean isMinioConnected(Document user) {
        Document credentials = DUUIUserController.getMinioCredentials(user);
        if (isNullOrEmpty(credentials)) return false;

        String accessKey = credentials.getString("access_key");
        if (isNullOrEmpty(accessKey)) return false;

        String secretKey = credentials.getString("secret_key");
        if (isNullOrEmpty(secretKey)) return false;

        String endpoint = credentials.getString("endpoint");
        return !isNullOrEmpty(endpoint);
    }

    private static Document getConnectionsForUser(String oid) {
        return getConnectionsForUser(getUserById(oid));
    }

    private static Document getConnectionsForUser(Document user) {
        return new Document("dropbox", isDropboxConnected(user))
            .append("minio", isMinioConnected(user));
    }

    public static String updateMinioCredentials(Request request, Response response) {
        String id = request.params(":id");
        Document body = Document.parse(request.body());
        Document credentials = body.get("minio", Document.class);

        String accessKey = credentials.getString("access_key");
        if (isNullOrEmpty(accessKey)) return missingField(response, "access_key");

        String secretKey = credentials.getString("secret_key");
        if (isNullOrEmpty(secretKey)) return missingField(response, "secret_key");

        String endpoint = credentials.getString("endpoint");
        if (isNullOrEmpty(endpoint)) return missingField(response, "endpoint");

        try {
            DUUIMinioDataReader reader = new DUUIMinioDataReader(
                endpoint,
                accessKey,
                secretKey);
        } catch (Exception e) {
            return fail(response, "Failed to connect with min.io");
        }
        Document update = new Document("minio",
            new Document("endpoint", endpoint)
                .append("access_key", accessKey)
                .append("secret_key", secretKey)
        );

        DUUIMongoDBStorage
            .getInstance()
            .getDatabase("duui")
            .getCollection("users")
            .findOneAndUpdate(
                Filters.eq(new ObjectId(id)),
                combineUpdates(update, _fields)
            );

        response.status(200);
        Document user = getUserById(id);
        return user.toJson();
    }

    private static String fail(Response response, String message) {
        response.status(500);
        return new Document("message", message).toJson();
    }
}