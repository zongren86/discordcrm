package com.discordadmin.repository;

import com.discordadmin.entity.FriendExclusionUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

@Repository
public interface FriendExclusionUserRepository extends JpaRepository<FriendExclusionUser, Long> {
    List<FriendExclusionUser> findByMerchantIdAndUserId(Long merchantId, Long userId);
    Page<FriendExclusionUser> findByMerchantIdAndUserId(Long merchantId, Long userId, Pageable pageable);
    long countByMerchantIdAndUserId(Long merchantId, Long userId);

    @Query("SELECT u.username FROM FriendExclusionUser u WHERE u.merchantId = :merchantId AND u.userId = :userId")
    List<String> findUsernamesByMerchantAndUser(@Param("merchantId") Long merchantId, @Param("userId") Long userId);

    boolean existsByMerchantIdAndUserIdAndUsernameIn(Long merchantId, Long userId, Set<String> usernames);

    @Modifying
    @Query("DELETE FROM FriendExclusionUser u WHERE u.merchantId = :merchantId AND u.userId = :userId")
    int deleteAllByMerchantIdAndUserId(@Param("merchantId") Long merchantId, @Param("userId") Long userId);
}
