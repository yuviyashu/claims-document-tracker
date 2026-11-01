package com.yuvaraj.claims;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import org.crac.Core;
import org.crac.Resource;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

public class DocumentHandler implements RequestHandler<S3Event, String>, Resource {

    // Created once per container and reused across invocations
    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    private static final String TABLE = "claim-documents";

    public DocumentHandler() {
        // Register for SnapStart runtime hooks
        Core.getGlobalContext().register(this);
    }

    @Override
    public String handleRequest(S3Event event, Context context) {
        for (var record : event.getRecords()) {
            var s3 = record.getS3();
            if (s3 == null || s3.getObject() == null || s3.getBucket() == null) {
                context.getLogger().log("WARN: skipping record without S3 details, eventName="
                        + record.getEventName());
                continue;
            }
            // S3 URL-encodes keys in events: "my file.pdf" arrives as "my+file.pdf"
            String key = URLDecoder.decode(s3.getObject().getKey(), StandardCharsets.UTF_8);

            Map<String, AttributeValue> item = Map.of(
                    "documentId", AttributeValue.fromS(key),
                    "bucket",     AttributeValue.fromS(s3.getBucket().getName()),
                    "sizeBytes",  AttributeValue.fromN(String.valueOf(s3.getObject().getSizeAsLong())),
                    "uploadedAt", AttributeValue.fromS(Instant.now().toString()));

            DYNAMO.putItem(r -> r.tableName(TABLE).item(item));
            context.getLogger().log("Indexed: " + key);
        }
        return "OK";
    }

    @Override
    public void beforeCheckpoint(org.crac.Context<? extends Resource> context) {
        // Priming: warm SDK classes, credentials and the TLS connection
        // so they are captured in the SnapStart snapshot
        DYNAMO.describeTable(r -> r.tableName(TABLE));
    }

    @Override
    public void afterRestore(org.crac.Context<? extends Resource> context) {
        // No action needed after restore
    }
}