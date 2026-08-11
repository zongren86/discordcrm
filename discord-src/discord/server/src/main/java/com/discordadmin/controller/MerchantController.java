package com.discordadmin.controller;

import com.discordadmin.entity.Merchant;
import com.discordadmin.repository.MerchantRepository;
import com.discordadmin.security.SecurityUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    private final MerchantRepository merchantRepository;

    public MerchantController(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @GetMapping
    public List<Merchant> list() {
        return merchantRepository.findAll();
    }

    @PostMapping
    public Merchant create(@RequestBody MerchantRequest req) {
        if (!SecurityUtils.isPlatformAdmin()) {
            throw new IllegalStateException("仅平台管理员可创建商户");
        }
        Merchant m = new Merchant();
        m.setName(req.name());
        m.setCode(req.code());
        m.setContact(req.contact());
        m.setPhone(req.phone());
        m.setStatus("ACTIVE");
        return merchantRepository.save(m);
    }

    @PutMapping("/{id}")
    public Merchant update(@PathVariable Long id, @RequestBody MerchantRequest req) {
        Merchant m = merchantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("商户不存在"));
        if (req.name() != null) m.setName(req.name());
        if (req.code() != null) m.setCode(req.code());
        if (req.contact() != null) m.setContact(req.contact());
        if (req.phone() != null) m.setPhone(req.phone());
        if (req.status() != null) m.setStatus(req.status());
        return merchantRepository.save(m);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        if (!SecurityUtils.isPlatformAdmin()) {
            throw new IllegalStateException("仅平台管理员可删除商户");
        }
        merchantRepository.deleteById(id);
    }

    public record MerchantRequest(String name, String code, String contact, String phone, String status) {}
}
