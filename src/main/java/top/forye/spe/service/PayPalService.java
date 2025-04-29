package top.forye.spe.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import top.forye.spe.component.PayPalClient;
import top.forye.spe.model.Pair;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Service
public class PayPalService {
    @Resource
    private PayPalClient client;

    private static final Logger logger = Logger.getLogger(PayPalService.class.getName());

    public boolean verify(Map<String, String> headers, JsonNode body) {
        return client.verify(headers, body);
    }

    public boolean callback(JsonNode data) {
        // 获取回调类型
        String type = data.path("event_type").asText();
        // https://developer.paypal.com/dashboard/webhooks/sandbox
        return switch (type) {
            case "CHECKOUT.ORDER.APPROVED" -> {
                // 获取订单支付后的token
                String token = data.path("resource").path("id").asText();
                // 到这里，token对应的订单才算是到账
                // 用该token获取订单信息（基本都是用数据库保存），设置该订单为已支付状态
                // 同时获取该订单的captureId，方便之后退款
                String captureId = client.captureOrder(token);
                logger.info("订单%s已到账，captureId为 %s".formatted(token, captureId));
                yield true;
            }
            // 调用capture_order就会回调这个类型
            case "PAYMENT.CAPTURE.COMPLETED" -> true;
            // 其他回调类型查文档
            default -> false;
        };
    }

    public String create(Double price, String description) {
        Pair<String, String> result = client.createOrder(UUID.randomUUID().toString(), new BigDecimal(price), description);
        if (result == null) {
            return "订单创建失败";
        }
        return "订单创建成功：token: %s 支付链接：%s".formatted(result.first(), result.second());
    }

    public String refund(String captureId, Double price, String description) {
        boolean result = client.refundOrder(captureId, new BigDecimal(price), description);
        if (result) {
            return "订单退款成功";
        }
        return "订单退款失败";
    }
}
