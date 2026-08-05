package com.enrola.agent.engine;

import com.enrola.agent.conversation.Conversation;
import com.enrola.agent.conversation.Message;
import com.enrola.agent.conversation.MessageDirection;
import com.enrola.agent.customer.CustomerConfig;
import com.enrola.agent.lead.Lead;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    private static final DateTimeFormatter HUMAN =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a");

    private final Clock clock;

    public PromptBuilder(Clock clock) {
        this.clock = clock;
    }

    public List<InputItem> build(CustomerConfig customer, Lead lead, Conversation conversation,
                                 List<Message> history, String inbound) {
        var items = new ArrayList<InputItem>();
        items.add(InputItem.system(customer.prompt().content()));
        items.add(InputItem.system("REFERENCE MATERIAL ABOUT THE CUSTOMER\n\n"
                + customer.infoPack().content()));
        items.add(InputItem.system(runtimeContext(customer, lead, conversation)));

        for (var message : history) {
            items.add(message.direction() == MessageDirection.OUTBOUND
                    ? InputItem.assistant(message.body())
                    : InputItem.user(message.body()));
        }

        items.add(inbound == null
                ? InputItem.developer("Send the opening message now.")
                : InputItem.user(inbound));
        return items;
    }

    private String runtimeContext(CustomerConfig customer, Lead lead, Conversation conversation) {
        var now = clock.instant().atZone(customer.timezone());
        return """
            RUNTIME CONTEXT

            Your name: %s
            Character limit for each message: %d
            Current date and time in the lead's timezone (%s): %s
            Objections this lead has already raised: %d

            THE LEAD
            Given name: %s
            State: %s
            Current health insurer: %s
            Current monthly premium: %s
            """.formatted(
                customer.agentName(),
                customer.smsCharLimit(),
                customer.timezone(),
                HUMAN.format(now),
                conversation.objectionCount(),
                lead.givenName(),
                lead.state(),
                lead.currentProvider() == null ? "none" : lead.currentProvider(),
                lead.currentPremium() == null ? "unknown" : lead.currentPremium());
    }
}
