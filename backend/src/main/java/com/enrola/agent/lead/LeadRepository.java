package com.enrola.agent.lead;

import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface LeadRepository extends CrudRepository<Lead, Long> {
    List<Lead> findByCustomerId(String customerId);
}
