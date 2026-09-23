package com.pgy.gateway.infrastructure.persistence.repo;

import com.pgy.gateway.infrastructure.persistence.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 虚拟密钥的数据访问接口。
 * 方法名遵守 Spring Data 的约定（findBy + 字段名），框架会自动生成 SQL。
 */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

    Optional<ApiKeyEntity> findByKeyValue(String keyValue);
}
