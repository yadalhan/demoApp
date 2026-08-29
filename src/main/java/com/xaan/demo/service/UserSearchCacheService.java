package com.xaan.demo.service;

import com.xaan.demo.domain.entity.User;
import com.xaan.demo.domain.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Split out from {@link UserService} deliberately - {@code @Cacheable} only works through
 * Spring's proxy, and a method calling another {@code @Cacheable} method on {@code this}
 * (self-invocation) bypasses that proxy entirely, silently never caching anything. Keeping
 * the cached method on a separate bean means {@link UserService} calls it through a real
 * Spring-managed reference, so the proxy - and the caching - actually engages.
 */
@RequiredArgsConstructor
@Service
public class UserSearchCacheService {
    private final UserMapper userMapper;

    // 캐싱 대상은 반드시 이 raw 조회여야 한다 - userMapper.search()가 id_no/phone을 복호화하지 않고
    // 그대로 반환하므로(UserMapper 주석 참고), Redis에 저장되는 값도 항상 ciphertext뿐이다.
    @Cacheable(value = "userSearchRaw", key = "(#name ?: '') + '|' + (#phoneBlindIndex ?: '') + '|' + (#rrnBlindIndex ?: '')")
    public List<User> search(String name, String phoneBlindIndex, String rrnBlindIndex) {
        return userMapper.search(name, phoneBlindIndex, rrnBlindIndex);
    }
}
