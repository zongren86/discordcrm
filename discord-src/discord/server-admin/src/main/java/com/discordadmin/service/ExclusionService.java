package com.discordadmin.service;

import com.discordadmin.entity.*;
import com.discordadmin.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 好友排除配置服务
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ 排除判断逻辑 (markExcludedAfterServerSync)                        │
 * │                                                                  │
 * │ 1. 先读商户的 FriendExclusionConfig                              │
 * │ 2. 若 excludeAllFriends=true → 查该商户已成功添加的所有好友名      │
 * │ 3. 若 useCustomList=true     → 查 FriendExclusionUser.username   │
 * │ 4. 合并两个集合 (去重)                                           │
 * │ 5. 在指定 GuildServer 的 GuildMember 中, 匹配 username,           │
 * │    将 friend_status=0(PENDING) 改为 friend_status=4(EXCLUDED)    │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * 注意: friend_status Integer 映射
 *   0=PENDING(待添加) 1=ASSIGNED(已分配) 2=SUCCESS(成功) 3=FAILED(失败) 4=EXCLUDED(排除)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExclusionService {

    private final FriendExclusionConfigRepository configRepo;
    private final FriendExclusionUserRepository userRepo;
    private final GuildMemberRepository guildMemberRepo;
    private final FriendRepository friendRepo;

    // ======== 商户配置 CRUD ========

    public Map<String, Object> getConfig(Long merchantId, Long userId) {
        FriendExclusionConfig cfg = configRepo.findByMerchantIdAndUserId(merchantId, userId)
                .orElseGet(() -> {
                    FriendExclusionConfig c = new FriendExclusionConfig();
                    c.setMerchantId(merchantId);
                    c.setUserId(userId);
                    c.setExcludeAllFriends(false);
                    c.setUseCustomList(false);
                    return c;
                });
        Map<String, Object> m = new HashMap<>();
        m.put("id", cfg.getId());
        m.put("excludeAllFriends", cfg.getExcludeAllFriends());
        m.put("useCustomList", cfg.getUseCustomList());

        // 实时统计
        long customCount = userRepo.countByMerchantIdAndUserId(merchantId, userId);
        m.put("customListCount", customCount);

        // 已排除的服务器成员总数 (跨所有服务器)
        Long excludedCount = guildMemberRepo.countWithFriendStatusByMerchant(merchantId);
        m.put("totalExcludedMembers", excludedCount != null ? excludedCount : 0L);

        return m;
    }

    @Transactional
    public FriendExclusionConfig saveConfig(Long merchantId, Long userId,
                                            Boolean excludeAllFriends, Boolean useCustomList) {
        FriendExclusionConfig cfg = configRepo.findByMerchantIdAndUserId(merchantId, userId)
                .orElseGet(() -> {
                    FriendExclusionConfig c = new FriendExclusionConfig();
                    c.setMerchantId(merchantId);
                    c.setUserId(userId);
                    return c;
                });
        cfg.setExcludeAllFriends(Boolean.TRUE.equals(excludeAllFriends));
        cfg.setUseCustomList(Boolean.TRUE.equals(useCustomList));
        cfg.setUpdatedAt(Instant.now());
        return configRepo.save(cfg);
    }

    // ======== 指定清单 CRUD ========

    public Page<FriendExclusionUser> getCustomPage(Long merchantId, Long userId, org.springframework.data.domain.Pageable pageable) {
        return userRepo.findByMerchantIdAndUserId(merchantId, userId, pageable);
    }

    public List<String> getCustomUsernames(Long merchantId, Long userId) {
        return userRepo.findUsernamesByMerchantAndUser(merchantId, userId);
    }

    @Transactional
    public int uploadFromExcel(Long merchantId, Long userId, MultipartFile file) throws Exception {
        List<String> names = parseExcelUsernames(file.getInputStream());
        return addUsernames(merchantId, userId, names, "upload_" + Instant.now().toEpochMilli());
    }

    @Transactional
    public int replaceAllFromExcel(Long merchantId, Long userId, MultipartFile file) throws Exception {
        List<String> names = parseExcelUsernames(file.getInputStream());
        userRepo.deleteAllByMerchantIdAndUserId(merchantId, userId);
        return addUsernames(merchantId, userId, names, "replace_" + Instant.now().toEpochMilli());
    }

    @Transactional
    public int addUsernames(Long merchantId, Long userId, List<String> rawNames, String source) {
        // 清洗 + 去重
        Set<String> cleaned = rawNames.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith("@") ? s.substring(1) : s)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (cleaned.isEmpty()) return 0;

        int added = 0;
        for (String name : cleaned) {
            FriendExclusionUser u = new FriendExclusionUser();
            u.setMerchantId(merchantId);
            u.setUserId(userId);
            u.setUsername(name);
            u.setSource(source);
            userRepo.save(u);
            added++;
        }
        return added;
    }

    @Transactional
    public int deleteById(Long id) {
        if (!userRepo.existsById(id)) return 0;
        userRepo.deleteById(id);
        return 1;
    }

    @Transactional
    public int clearAll(Long merchantId, Long userId) {
        return userRepo.deleteAllByMerchantIdAndUserId(merchantId, userId);
    }

    // ======== Excel 模板下载 ========

    public byte[] generateTemplate() {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("排除用户名清单");

            // 表头
            Row header = sheet.createRow(0);
            Cell h1 = header.createCell(0);
            h1.setCellValue("username");
            CellStyle hStyle = wb.createCellStyle();
            hStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            hStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            h1.setCellStyle(hStyle);

            // 示例行
            String[] examples = {"discord_user_1", "warband_fan", "crypto_trader"};
            for (int i = 0; i < examples.length; i++) {
                Row r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue(examples[i]);
            }
            sheet.setColumnWidth(0, 6000);
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成模板失败", e);
        }
    }

    // ======== 核心: 服务器同步后自动标记 EXCLUDED ========

    /**
     * 服务器成员采集完成后调用
     * 遍历所有 GuildServer (或指定 serverIds), 将命中排除清单的成员标记 friend_status=4
     *
     * @param merchantId 商户 ID
     * @param userId     用户 ID
     * @param serverIds  只处理这些服务器 (null=全部)
     * @return 被标记 EXCLUDED 的成员数
     */
    @Transactional
    public int markExcludedAfterServerSync(Long merchantId, Long userId, List<Long> serverIds) {
        FriendExclusionConfig cfg = configRepo.findByMerchantIdAndUserId(merchantId, userId).orElse(null);
        if (cfg == null || (!Boolean.TRUE.equals(cfg.getExcludeAllFriends()) && !Boolean.TRUE.equals(cfg.getUseCustomList()))) {
            return 0; // 没开任何排除规则 → 直接跳过
        }

        Set<String> excludedNames = collectExcludedUsernames(merchantId, userId, cfg);
        if (excludedNames.isEmpty()) return 0;

        log.info("排除标记开始: merchant={}, 排除名单大小={}, serverCount={}",
                merchantId, excludedNames.size(), serverIds == null ? "ALL" : serverIds.size());

        // 批量更新: GuildMember 中 friend_status=0(PENDING) 且 username 在 excludedNames 中 → 改为 4(EXCLUDED)
        int updated;
        if (serverIds != null && !serverIds.isEmpty()) {
            updated = guildMemberRepo.markExcludedInServers(serverIds, excludedNames);
        } else {
            updated = guildMemberRepo.markExcludedGlobal(merchantId, excludedNames);
        }

        log.info("排除标记完成: 更新 {} 个成员 → EXCLUDED", updated);
        return updated;
    }

    /** 收集所有需要排除的用户名 (lowercase 去重) */
    private Set<String> collectExcludedUsernames(Long merchantId, Long userId, FriendExclusionConfig cfg) {
        Set<String> names = new HashSet<>();

        if (Boolean.TRUE.equals(cfg.getExcludeAllFriends())) {
            // 选项一: 已成功添加的好友 (friend.status=SUCCESS → Friend.FriendStatus.ACCEPTED 对应值)
            List<Friend> addedFriends = friendRepo.findByMerchantIdAndStatus(merchantId, Friend.FriendStatus.ACCEPTED);
            for (Friend f : addedFriends) {
                if (f.getUsername() != null) names.add(f.getUsername().toLowerCase());
                if (f.getGlobalName() != null) names.add(f.getGlobalName().toLowerCase());
            }
        }

        if (Boolean.TRUE.equals(cfg.getUseCustomList())) {
            List<String> custom = userRepo.findUsernamesByMerchantAndUser(merchantId, userId);
            custom.forEach(n -> names.add(n.toLowerCase()));
        }

        return names;
    }

    // ======== 内部工具 ========

    /** 解析 Excel 第一列为用户名 */
    private List<String> parseExcelUsernames(InputStream in) throws Exception {
        List<String> result = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return result;
            int startRow = 0;
            // 跳过表头: 如果第一行包含 "username"/"用户名" 字样
            Row header = sheet.getRow(0);
            if (header != null) {
                String firstCell = getCellString(header.getCell(0)).toLowerCase();
                if (firstCell.contains("username") || firstCell.contains("用户名")) {
                    startRow = 1;
                }
            }
            for (int i = startRow; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String val = getCellString(row.getCell(0));
                if (!val.isBlank()) result.add(val.trim());
            }
        }
        return result;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }
}
