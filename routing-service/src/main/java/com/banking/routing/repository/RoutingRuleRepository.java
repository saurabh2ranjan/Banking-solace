package com.banking.routing.repository;

import com.banking.routing.entity.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoutingRuleRepository extends JpaRepository<RoutingRule, String> {

    List<RoutingRule> findByActiveTrue();
}
