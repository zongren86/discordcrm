package com.discordadmin.controller;

import com.discordadmin.service.AgentAccountNumberRelService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class AgentAccountNumberRelController {

    private final AgentAccountNumberRelService relService;

    public AgentAccountNumberRelController(AgentAccountNumberRelService relService) {
        this.relService = relService;
    }

    /** 获取用户关联的账号编号列表 */
    @GetMapping("/{id}/account-numbers")
    public List<Map<String, Object>> listByAgentId(@PathVariable Long id) {
        return relService.listByAgentId(id);
    }

    /** 批量关联账号编号给用户（按自定义编号） */
    @PostMapping("/{id}/account-numbers")
    public Map<String, Object> batchLinkNumbers(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String rangeStr = body.get("range");
        List<Long> parsedNos = relService.parseNumberRange(rangeStr);
        // 转换为 Integer 的 customNo 列表
        List<Integer> customNos = parsedNos.stream()
                .map(Long::intValue)
                .toList();
        relService.batchLinkByCustomNos(id, customNos);
        
        Map<String, Object> result = Map.of("success", true, "count", customNos.size());
        return result;
    }

    /** 删除单条关联 */
    @DeleteMapping("/{id}/account-numbers/{accountNumberId}")
    public Map<String, Object> unlinkNumber(
            @PathVariable Long id,
            @PathVariable Long accountNumberId) {
        relService.unlinkNumber(id, accountNumberId);
        return Map.of("success", true);
    }
}
