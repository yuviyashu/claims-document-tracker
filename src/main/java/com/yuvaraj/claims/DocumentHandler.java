package com.yuvaraj.claims;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

public class DocumentHandler implements RequestHandler<S3Event, String> {

    // Created once per Lambda container and reused across calls (faster warm starts)
    private static final DynamoDbClient DYNAMO = DynamoDbClient.create();
    private static final String TABLE = "claim-documents";

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        for (var record : s3Event.getRecords()) {
            var s3 = record.getS3();
            // S3 encodes keys in events: "my file.pdf" arrives as "my+file.pdf"
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
}
