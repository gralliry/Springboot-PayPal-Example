package top.forye.spe.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import top.forye.spe.config.PayPalConfig;
import top.forye.spe.model.Pair;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PayPalClient {
    @Resource
    private PayPalConfig config;

    private static final ObjectMapper mapper = new ObjectMapper();

    private String accessToken;

    private long expiresTime;


    private URI getUri(String path) {
        return URI.create((config.getTest() ? "https://api.sandbox.paypal.com" : "https://api.paypal.com") + path);
    }

    // 获取 Access Token
    private String getAccessToken() {
        if (accessToken != null && expiresTime > System.currentTimeMillis()) {
            return accessToken;
        }
        // 发送请求并获取响应
        try (HttpClient client = HttpClient.newHttpClient()) {
            String encodeToken = Base64.getEncoder().encodeToString(String.format("%s:%s", config.getClientId(), config.getSecretKey()).getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(getUri("/v1/oauth2/token"))
                    .header("Authorization", "Basic " + encodeToken)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return accessToken;
            }
            String body = response.body();
            JsonNode root = mapper.readTree(body);
            // 提取 access_token 和 expires_in
            accessToken = root.get("access_token").asText();
            expiresTime = (root.get("expires_in").asLong() - 600) * 1000;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        return accessToken;
    }

    public boolean verify(Map<String, String> headers, JsonNode body) {
        // headers是不分大小写的，如果是大写需要转换一下
        Map<String, String> lowerHeaders = headers.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(),
                        Map.Entry::getValue
                ));
        String authAlgo = lowerHeaders.get("paypal-auth-algo");
        String certUrl = lowerHeaders.get("paypal-cert-url");
        String transmissionId = lowerHeaders.get("paypal-transmission-id");
        String transmissionSig = lowerHeaders.get("paypal-transmission-sig");
        String transmissionTime = lowerHeaders.get("paypal-transmission-time");
        if (authAlgo == null || certUrl == null || transmissionId == null || transmissionSig == null || transmissionTime == null) {
            return false;
        }
        // https://developer.paypal.com/docs/api/webhooks/v1/#verify-webhook-signature_post
        ObjectNode data = mapper.createObjectNode()
                .put("auth_algo", authAlgo)
                .put("cert_url", certUrl)
                .put("transmission_id", transmissionId)
                .put("transmission_sig", transmissionSig)
                .put("transmission_time", transmissionTime)
                .put("webhook_id", config.getWebhookId())
                .set("webhook_event", body);
        // 创建 HttpClient 实例
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(getUri("/v1/notifications/verify-webhook-signature"))
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .build();
            // 发送请求并获取响应
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                // webhook请求失败
                return false;
            }
            JsonNode resp = mapper.readTree(response.body());
            return "SUCCESS".equalsIgnoreCase(resp.path("verification_status").asText());
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public Pair<String, String> createOrder(String customId, BigDecimal price, String description) {
        // https://developer.paypal.com/docs/api/orders/v2/#orders_create
        // 这里在构造json，内容参数看官方文档
        // customId是你应用端的订单id,你可以自己生成，但是要保证唯一
        // price是订单价格，注意，最多小数点后两位，这里截断了小数点两位后的
        price = price.setScale(2, RoundingMode.DOWN);
        ObjectNode applicationContext = mapper.createObjectNode()
                .put("brand_name", "你的应用名字")
                .put("locale", "zh-CN")
                .put("landing_page", "NO_PREFERENCE")
                .put("user_action", "PAY_NOW")
                .put("shipping_preference", "GET_FROM_FILE")
                // 这里的地址，你是可以加参数的，可以添加签名参数，来使得同步回调地址变得安全，不至于被伪造
                // 但是不要覆盖掉Paypal自带的参数
                .put("return_url", config.getCancelUrl())
                .put("cancel_url", config.getCancelUrl());
        ObjectNode amount = mapper.createObjectNode()
                // Paypal不支持中国地区，所以用不了CNY，不然会出错，其它货币没试过，但是美元USD是可以使用的。
                .put("currency_code", "USD")
                .put("value", price);
        ObjectNode purchaseUnit = mapper.createObjectNode()
                .put("custom_id", customId)
                .put("description", description);
        purchaseUnit.set("amount", amount);
        purchaseUnit.set("shipping", mapper.createObjectNode());
        ArrayNode purchaseUnits = mapper.createArrayNode()
                .add(purchaseUnit);
        ObjectNode data = mapper.createObjectNode()
                .put("intent", "CAPTURE");
        data.set("purchase_units", purchaseUnits);
        data.set("application_context", applicationContext);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(getUri("/v2/checkout/orders"))
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("PayPal-Request-Id", UUID.randomUUID().toString())
                    .header("Prefer", "return=representation")
                    // 如果json格式，要添加这个，不然会报以下的错误
                    // {"name":"UNSUPPORTED_MEDIA_TYPE","message":"The request payload is not supported","debug_id":"eaaccb64ca68b","details":[],"links":[]}
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                // log.error("创建订单失败，code: {}\n{}", response.statusCode(), response.body());
                return null;
            }
            // 获取响应体
            JsonNode resp = mapper.readTree(response.body());
            // 获取链接
            JsonNode links = resp.path("links");
            // 获取到PayPal支付链接
            String payUrl = null;
            for (JsonNode link : links) {
                if ("approve".equalsIgnoreCase(link.path("rel").asText())) {
                    payUrl = link.path("href").asText();
                    break;
                }
            }
            // 获取token
            String token = resp.path("id").asText();
            // 封装返回对象
            if (!"CREATED".equalsIgnoreCase(data.path("status").asText()) || token == null || payUrl == null) {
                return null;
            }
            // 是否创建成功、token、支付链接，
            return Pair.of(token, payUrl);
        } catch (IOException | InterruptedException e) {
            // log.error("创建订单失败");
            return null;
        }
    }

    public String captureOrder(String token) {
        // 注意，每个订单只能被捕获一次
        // 返回captureId，该captureId用来发起退款
        // https://developer.paypal.com/docs/api/orders/v2/#orders_capture
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(getUri("/v2/checkout/orders/%s/capture".formatted(token)))
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("PayPal-Request-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                // log.info("订单捕获错误: {}\n{}", response.statusCode(), response.body());
                return null;
            }
            JsonNode resp = mapper.readTree(response.body());
            // 导航至目标节点并提取 "id" 值
            return resp
                    .path("purchase_units")
                    .path(0)
                    .path("payments")
                    .path("captures")
                    .path(0)
                    .path("id")
                    .asText();
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    public boolean refundOrder(String captureId, BigDecimal price, String description) {
        // 发起退款
        // 注意，参数是captureId，不是token
        // https://developer.paypal.com/docs/api/payments/v2/#captures_refund
        // price是订单价格，注意，最多小数点后两位，这里截断了小数点两位后的
        price = price.setScale(2, RoundingMode.DOWN);
        ObjectNode amount = mapper.createObjectNode()
                // 退款货币
                .put("currency_code", "USD")
                // 退款金额
                .put("value", price.toString());
        ObjectNode data = mapper.createObjectNode()
                // 退款说明
                .put("note_to_payer", description)
                // .put("invoice_id", "")
                .set("amount", amount);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(getUri("/v2/payments/captures/%s/refund".formatted(captureId)))
                    .header("Authorization", "Bearer " + getAccessToken())
                    .header("PayPal-Request-Id", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                return false;
            }
            JsonNode resp = mapper.readTree(response.body());
            String status = resp.path("status").asText();
            // 如果退款失败，回滚事务
            return "COMPLETED".equalsIgnoreCase(status);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
