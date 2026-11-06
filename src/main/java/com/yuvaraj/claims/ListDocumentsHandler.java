package com.yuvaraj.claims;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.crac.Core;
import org.crac.Resource;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ListDocumentsHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>, Resource {

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TABLE = "claim-documents";
    private static final int MAX_ITEMS = 100;

    public ListDocumentsHandler() {
        Core.getGlobalContext().register(this);
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            var response = DYNAMO.scan(r -> r.tableName(TABLE).limit(MAX_ITEMS));

            List<Map<String, Object>> documents = response.items().stream()
                    .map(ListDocumentsHandler::toDocument)
                    .toList();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("count", documents.size());
            body.put("documents", documents);

            return json(200, body);
        } catch (Exception e) {
            context.getLogger().log("ERROR: failed to list documents: " + e);
            return json(500, Map.of("error", "Could not list documents"));
        }
    }

    // Items are schemaless, so read each attribute defensively
    private static Map<String, Object> toDocument(Map<String, AttributeValue> item) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("documentId", text(item, "documentId"));
        doc.put("bucket", text(item, "bucket"));
        AttributeValue size = item.get("sizeBytes");
        doc.put("sizeBytes", size != null && size.n() != null ? Long.parseLong(size.n()) : null);
        doc.put("uploadedAt", text(item, "uploadedAt"));
        return doc;
    }

    private static String text(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value != null ? value.s() : null;
    }

    private static APIGatewayV2HTTPResponse json(int status, Object body) {
        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (Exception e) {
            payload = "{\"error\":\"Serialization failed\"}";
            status = 500;
        }
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(payload)
                .build();
    }

    @Override
    public void beforeCheckpoint(org.crac.Context<? extends Resource> context) throws Exception {
        // Prime the real code path: a 1-item scan plus JSON serialization
        var sample = DYNAMO.scan(r -> r.tableName(TABLE).limit(1));
        JSON.writeValueAsString(sample.items().stream().map(ListDocumentsHandler::toDocument).toList());
    }

    @Override
    public void afterRestore(org.crac.Context<? extends Resource> context) {
        // No action needed after restore
    }
}