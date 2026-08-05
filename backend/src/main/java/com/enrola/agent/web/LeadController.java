package com.enrola.agent.web;

import com.enrola.agent.lead.LeadRepository;
import com.enrola.agent.web.Dtos.LeadDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/leads")
class LeadController {

    private final LeadRepository leads;

    LeadController(LeadRepository leads) {
        this.leads = leads;
    }

    @GetMapping
    List<LeadDto> all() {
        return java.util.stream.StreamSupport.stream(leads.findAll().spliterator(), false)
                .map(LeadDto::of).toList();
    }
}
