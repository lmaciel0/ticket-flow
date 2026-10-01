package com.ticketflow.demo;

import com.ticketflow.category.Category;
import com.ticketflow.category.CategoryRepository;
import com.ticketflow.comment.Comment;
import com.ticketflow.comment.CommentRepository;
import com.ticketflow.history.HistoryEventType;
import com.ticketflow.history.HistoryRecorder;
import com.ticketflow.sla.SlaCalculator;
import com.ticketflow.ticket.Priority;
import com.ticketflow.ticket.Ticket;
import com.ticketflow.ticket.TicketRepository;
import com.ticketflow.ticket.TicketStatus;
import com.ticketflow.user.Role;
import com.ticketflow.user.User;
import com.ticketflow.user.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills the public demo with users and ~30 tickets whose dates are relative to "now", so the
 * list always shows overdue, at-risk and on-track tickets. Runs at startup (profile "demo") and
 * resets everything when the last reset is older than 24h — the free host sleeps a lot, so
 * "at startup" happens often enough and needs no scheduler.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "demo1234";
    static final Duration RESET_INTERVAL = Duration.ofHours(24);
    static final int TICKET_COUNT = 30;

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String[][] SAMPLES = {
            {"Sem acesso ao sistema financeiro", "Depois da troca de senha não consigo entrar no sistema."},
            {"Impressora do 2º andar não imprime", "A impressora mostra 'papel atolado', mas não há papel preso."},
            {"Notebook muito lento", "O notebook demora vários minutos para abrir qualquer programa."},
            {"Instalar software de design", "Preciso do editor de imagens instalado para o projeto novo."},
            {"Reembolso de despesa não aparece", "Enviei o reembolso há uma semana e ele não aparece no sistema."},
            {"VPN desconecta toda hora", "A VPN cai a cada 10 minutos quando trabalho de casa."},
            {"Criar acesso para estagiário", "O estagiário começa segunda e precisa de e-mail e acesso à rede."},
            {"Monitor piscando", "O monitor secundário pisca e às vezes apaga."},
            {"Erro ao gerar nota fiscal", "O sistema mostra 'erro 500' ao emitir a nota fiscal."},
            {"Trocar teclado quebrado", "Algumas teclas do teclado pararam de funcionar."},
    };

    /** Fraction of the deadline already used by running tickets: on track, at risk, overdue. */
    private static final double[] RUNNING_AGE = {0.3, 0.85, 1.5};

    /** Fraction of the first-response deadline used before the agent took the ticket: two on time, one late. */
    private static final double[] FIRST_RESPONSE_PACE = {0.4, 0.7, 1.4};

    private enum Scenario { OPEN, IN_PROGRESS, WAITING, RESOLVED, CLOSED }

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final TicketRepository tickets;
    private final CommentRepository comments;
    private final HistoryRecorder history;
    private final SlaCalculator sla;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public DemoDataSeeder(JdbcTemplate jdbc, UserRepository users, CategoryRepository categories,
            TicketRepository tickets, CommentRepository comments, HistoryRecorder history, SlaCalculator sla,
            PasswordEncoder passwordEncoder, Clock clock, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.users = users;
        this.categories = categories;
        this.tickets = tickets;
        this.comments = comments;
        this.history = history;
        this.sla = sla;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.transaction = transaction;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (resetIfDue()) {
            log.info("Demo data reset.");
        }
    }

    /** Returns true when the data was (re)created. */
    public boolean resetIfDue() {
        Instant now = clock.instant();
        List<Timestamp> lastReset = jdbc.queryForList(
                "SELECT last_reset_at FROM demo_state WHERE id = 1", Timestamp.class);
        if (!lastReset.isEmpty() && lastReset.getFirst().toInstant().plus(RESET_INTERVAL).isAfter(now)) {
            return false;
        }
        // TransactionTemplate instead of @Transactional: a method calling another method of the
        // same object bypasses the Spring proxy, so @Transactional there would be silently ignored.
        transaction.executeWithoutResult(status -> {
            wipe();
            seed(now);
            jdbc.update("""
                    INSERT INTO demo_state (id, last_reset_at) VALUES (1, ?)
                    ON CONFLICT (id) DO UPDATE SET last_reset_at = EXCLUDED.last_reset_at
                    """, Timestamp.from(now));
        });
        return true;
    }

    /**
     * No RESTART IDENTITY on purpose: ids keep growing, so an old token (whose "sub" is a deleted
     * user id) can never point to a different, newly created user.
     */
    private void wipe() {
        jdbc.execute("TRUNCATE attachment_content, attachments, comments, ticket_history, tickets, users CASCADE");
    }

    private void seed(Instant now) {
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        User ana = demoUser("Ana Souza (Solicitante)", "solicitante@ticketflow.demo", Role.REQUESTER, hash, now);
        User elisa = demoUser("Elisa Rocha", "elisa@ticketflow.demo", Role.REQUESTER, hash, now);
        User bruno = demoUser("Bruno Lima (Atendente)", "atendente@ticketflow.demo", Role.AGENT, hash, now);
        User diego = demoUser("Diego Alves", "diego@ticketflow.demo", Role.AGENT, hash, now);
        demoUser("Carla Mendes (Gestora)", "gestor@ticketflow.demo", Role.MANAGER, hash, now);

        List<Category> allCategories = categories.findAllByOrderByIdAsc();
        Random random = new Random(42);
        for (int i = 0; i < TICKET_COUNT; i++) {
            Scenario scenario = Scenario.values()[i % Scenario.values().length];
            Priority priority = Priority.values()[random.nextInt(Priority.values().length)];
            User requester = random.nextBoolean() ? ana : elisa;
            User agent = random.nextBoolean() ? bruno : diego;
            Category category = allCategories.get(random.nextInt(allCategories.size()));
            String[] sample = SAMPLES[i % SAMPLES.length];

            double running = RUNNING_AGE[i % RUNNING_AGE.length];
            boolean newcomer = (i / Scenario.values().length) % 2 == 0;
            Duration age = switch (scenario) {
                // Half the open tickets are new (first response pending or overdue); the other half and the
                // in-progress ones keep the on-track / at-risk / overdue mix of the resolution SLA.
                case OPEN -> scale(newcomer ? sla.firstResponseDeadlineFor(priority) : sla.deadlineFor(priority),
                        running);
                case IN_PROGRESS -> scale(sla.deadlineFor(priority), running);
                default -> Duration.ofHours(6 + random.nextInt(24 * 6));
            };
            // Aged on the SLA clock: with business hours on, the mix of overdue/at-risk tickets must not
            // depend on the time of day the demo happens to be reset.
            Instant createdAt = sla.ago(now, age);
            Ticket ticket = tickets.save(
                    new Ticket(sample[0], sample[1], priority, category, requester, createdAt, sla));
            history.record(ticket, requester, HistoryEventType.CREATED, createdAt);
            if (scenario != Scenario.OPEN) {
                play(ticket, scenario, agent, requester, createdAt, age,
                        FIRST_RESPONSE_PACE[i % FIRST_RESPONSE_PACE.length]);
            }
        }
    }

    /** Replays the lifecycle with the real domain methods, at moments between creation and now. */
    private void play(Ticket ticket, Scenario scenario, User agent, User requester, Instant createdAt,
            Duration age, double firstResponsePace) {
        // Every step is placed on the SLA clock, like the age itself, so whether a deadline is met or breached
        // does not depend on the hour of the reset. The first response never comes after 30% of the age, so it
        // stays before the next steps; capping only moves it earlier, so it never turns an on-time answer into
        // a late one.
        Instant firstResponse = sla.after(createdAt,
                scale(sla.firstResponseDeadlineFor(ticket.getPriority()), firstResponsePace));
        Instant latest = sla.after(createdAt, scale(age, 0.3));
        Instant assignedAt = firstResponse.isBefore(latest) ? firstResponse : latest;
        Instant secondStep = sla.after(createdAt, scale(age, 0.4));
        Instant thirdStep = sla.after(createdAt, scale(age, 0.7));

        ticket.assign(agent, agent, assignedAt, sla);
        history.record(ticket, agent, HistoryEventType.ASSIGNED, "assignee", null, agent.getName(), assignedAt);
        history.record(ticket, agent, HistoryEventType.STATUS_CHANGED, "status", "OPEN", "IN_PROGRESS", assignedAt);

        switch (scenario) {
            case WAITING -> {
                comments.save(new Comment(ticket, agent, "Pode enviar um print da tela com o erro?", secondStep));
                history.record(ticket, agent, HistoryEventType.COMMENT_ADDED, secondStep);
                changeStatus(ticket, TicketStatus.WAITING_REQUESTER, agent, secondStep);
            }
            case RESOLVED -> {
                comments.save(new Comment(ticket, agent, "Resolvido. Pode verificar, por favor?", secondStep));
                history.record(ticket, agent, HistoryEventType.COMMENT_ADDED, secondStep);
                changeStatus(ticket, TicketStatus.RESOLVED, agent, secondStep);
            }
            case CLOSED -> {
                changeStatus(ticket, TicketStatus.RESOLVED, agent, secondStep);
                changeStatus(ticket, TicketStatus.CLOSED, requester, thirdStep);
            }
            default -> {
                // IN_PROGRESS: assigning was enough.
            }
        }
    }

    private void changeStatus(Ticket ticket, TicketStatus target, User actor, Instant at) {
        String old = ticket.getStatus().name();
        ticket.changeStatus(target, at, sla);
        history.record(ticket, actor, HistoryEventType.STATUS_CHANGED, "status", old, target.name(), at);
    }

    private User demoUser(String name, String email, Role role, String passwordHash, Instant now) {
        User user = new User(name, email, passwordHash, role, now);
        user.markAsDemo();
        return users.save(user);
    }

    private static Duration scale(Duration duration, double factor) {
        return Duration.ofSeconds((long) (duration.toSeconds() * factor));
    }
}
