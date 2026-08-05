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
        items.add(InputItem.system(runtimeContext(customer, conversation, history)));
        items.add(InputItem.developer(leadFacts(lead)));

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

    /**
     * The character limit shown here is the one {@code AgentService} will enforce on the very
     * message this prompt produces, footer included - see {@code Guardrails.charBudget}. Showing
     * the raw SMS limit instead would hand the model a budget ~25 characters too generous on the
     * opening message and get its output truncated for going over a line it was never told about.
     *
     * The timezone is the advisor's, and is labelled as such. It is the customer's configured
     * zone, and the leads are not all in it - telling the model it is the lead's zone would be
     * a plain falsehood the model then reasons from when it offers times.
     */
    private String runtimeContext(CustomerConfig customer, Conversation conversation,
                                  List<Message> history) {
        var now = clock.instant().atZone(customer.timezone());
        return """
            RUNTIME CONTEXT

            Your name: %s
            Character limit for each message: %d
            Current date and time in the advisor's timezone (%s): %s
            Objections this lead has already raised: %d
            """.formatted(
                customer.agentName(),
                Guardrails.charBudget(customer.smsCharLimit(), history),
                customer.timezone(),
                HUMAN.format(now),
                conversation.objectionCount());
    }

    /**
     * Developer role, not system. Every value here came off a web form the lead filled in, so
     * it is untrusted text; at system role it would sit at the same authority as the guardrails
     * above it and a "given name" of a paragraph of instructions would read as one.
     */
    private String leadFacts(Lead lead) {
        return """
            THE LEAD
            Given name: %s
            State: %s
            Current health insurer: %s
            Current monthly premium: %s
            """.formatted(
                lead.givenName(),
                lead.state(),
                lead.currentProvider() == null ? "none" : lead.currentProvider(),
                lead.currentPremium() == null ? "unknown" : lead.currentPremium());
    }
}
