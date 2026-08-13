package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在明确的只读事务内物化工单及其 LOB 字段，供异步 Agent 使用脱离持久化上下文的稳定快照。
 */
@Service
public class AfterSalesTicketContextService {
    private final AfterSalesTicketRepository ticketRepository;

    public AfterSalesTicketContextService(AfterSalesTicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional(readOnly = true)
    public TicketContext load(String ticketId) {
        AfterSalesTicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("TICKET_NOT_FOUND"));
        String customerMessage = ticket.getCustomerMessage();
        if (customerMessage != null) {
            customerMessage.length(); // 在事务内强制物化 PostgreSQL CLOB。
        }
        return new TicketContext(ticket, customerMessage == null ? "" : customerMessage);
    }

    public record TicketContext(AfterSalesTicketEntity ticket, String customerMessage) {
    }
}