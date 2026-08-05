package com.enrola.agent.web;

import com.enrola.agent.conversation.BookingRepository;
import com.enrola.agent.conversation.Conversation;
import com.enrola.agent.conversation.ConversationRepository;
import com.enrola.agent.conversation.MessageRepository;
import com.enrola.agent.customer.CustomerRegistry;
import com.enrola.agent.engine.AgentService;
import com.enrola.agent.lead.LeadRepository;
import com.enrola.agent.web.Dtos.ConversationDto;
import com.enrola.agent.web.Dtos.InboundRequest;
import com.enrola.agent.web.Dtos.StartRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
class ConversationController {

    private final AgentService agent;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final BookingRepository bookings;
    private final LeadRepository leads;
    private final CustomerRegistry customers;

    ConversationController(AgentService agent, ConversationRepository conversations,
                           MessageRepository messages, BookingRepository bookings,
                           LeadRepository leads, CustomerRegistry customers) {
        this.agent = agent;
        this.conversations = conversations;
        this.messages = messages;
        this.bookings = bookings;
        this.leads = leads;
        this.customers = customers;
    }

    @PostMapping
    ConversationDto start(@RequestBody StartRequest request) {
        return view(agent.start(request.leadId()));
    }

    @GetMapping("/{id}")
    ConversationDto get(@PathVariable Long id) {
        return view(conversations.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Unknown conversation: " + id)));
    }

    @PostMapping("/{id}/messages")
    ConversationDto inbound(@PathVariable Long id, @RequestBody InboundRequest request) {
        return view(agent.handleInbound(id, request.body()));
    }

    @PostMapping("/{id}/reset")
    ConversationDto reset(@PathVariable Long id) {
        return view(agent.reset(id));
    }

    private ConversationDto view(Conversation conversation) {
        var lead = leads.findById(conversation.leadId()).orElseThrow();
        var customer = customers.get(conversation.customerId());
        return ConversationDto.of(conversation, lead, customer.smsCharLimit(),
                messages.findByConversationIdOrderByIdAsc(conversation.id()),
                bookings.findByConversationId(conversation.id()));
    }
}
