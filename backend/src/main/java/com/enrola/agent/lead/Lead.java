package com.enrola.agent.lead;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("leads")
public record Lead(
        @Id Long id,
        String customerId,
        String givenName,
        String phone,
        String state,
        String email,
        String currentProvider,
        String currentPremium) {}
