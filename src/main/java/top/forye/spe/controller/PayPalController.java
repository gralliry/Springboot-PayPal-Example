package top.forye.spe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.forye.spe.service.PayPalService;

import java.util.Map;

@RestController
public class PayPalController {
    @Resource
    private PayPalService payPalService;

    public ResponseEntity<Void> notify(
            @RequestHeader Map<String, String> headers,
            @RequestBody JsonNode body
    ) {
        // 该接口必须是公网可以访问的
        // 验证回调请求 // 校验支付正确性
        if (!payPalService.verify(headers, body)) {
            // 验证失败，不是 PayPal 发起的回调请求
            return ResponseEntity.badRequest().build();
        }
        // 这里执行订单回调
        if (payPalService.callback(body)) {
            // 回调执行失败，返回错误码告知该回调处理失败，PayPal 会隔一段时间重试该回调
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping(value = "/return")
    public String return_(
            @RequestParam("token") String token,
            @RequestParam("PayerID") String payerId
    ) {
        // 注意，这个请求是可以被伪造的，不要认为这个接口是可以确认到账的
        return "你已付款，请等待订单到账 (支付token: {%s} 支付者id: {%s})".formatted(token, payerId);
    }

    @GetMapping(value = "/cancel")
    public String cancel(
            @RequestParam("token") String token
    ) {
        // 注意，这个请求也是可以被伪造的
        return "你已取消付款 (支付token: {%s})".formatted(token);
    }

    @PostMapping(value = "/create")
    public String create(
            @RequestParam("price") Double price,
            @RequestParam("description") String description
    ) {
        // 创建订单
        return payPalService.create(price, description);
    }

    @PostMapping(value = "/refund")
    public String refund(
            @RequestParam("captureId") String captureId,
            @RequestParam("price") Double price,
            @RequestParam("description") String description
    ) {
        // 退款
        return payPalService.refund(captureId, price, description);
    }
}
