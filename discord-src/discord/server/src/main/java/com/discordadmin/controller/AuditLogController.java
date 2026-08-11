package com.discordadmin.controller;

import com.discordadmin.entity.AuditLog;
import com.discordadmin.repository.AuditLogRepository;
import com.discordadmin.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogController(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<AuditLog> list(@RequestParam(required = false) String module,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) String operator,
                               @RequestParam(required = false) String dateFrom,
                               @RequestParam(required = false) String dateTo) {
        Long merchantId = SecurityUtils.currentMerchantId();
        boolean isPlatformAdmin = SecurityUtils.isPlatformAdmin();
        Long mid = isPlatformAdmin ? null : merchantId;

        Instant since = (dateFrom != null && !dateFrom.isBlank())
                ? LocalDate.parse(dateFrom).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : Instant.now().minusSeconds(86400L * 30);
        Instant until = (dateTo != null && !dateTo.isBlank())
                ? LocalDate.parse(dateTo).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
                : Instant.now();

        return auditLogRepository.search(mid, module, action, operator, since, until);
    }

    @GetMapping("/export")
    public String export(@RequestParam(required = false) String module,
                         @RequestParam(required = false) String action,
                         @RequestParam(required = false) String operator,
                         @RequestParam(required = false) String dateFrom,
                         @RequestParam(required = false) String dateTo) {
        List<AuditLog> logs = list(module, action, operator, dateFrom, dateTo);
        try {
            ArrayNode root = objectMapper.createArrayNode();
            for (AuditLog l : logs) {
                ObjectNode n = objectMapper.createObjectNode();
                n.put("id", l.getId());
                n.put("operator", l.getOperator());
                n.put("role", l.getOperatorRole());
                n.put("module", l.getModule());
                n.put("action", l.getAction());
                n.put("resourceType", l.getResourceType());
                n.put("resourceId", l.getResourceId());
                n.put("detail", l.getDetail());
                n.put("ip", l.getIp());
                n.put("result", l.getResult());
                n.put("createdAt", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
                root.add(n);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "[]";
        }
    }

    @GetMapping("/filters")
    public Map<String, List<String>> filterOptions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("modules", List.of("auth", "account", "conversation", "customer",
                "user", "merchant", "role", "system"));
        result.put("actions", List.of("CREATE", "UPDATE", "DELETE", "LOGIN", "LOGOUT", "EXPORT"));
        return result;
    }
}
