package com.example.conges.service;

import com.example.conges.dto.notification.EmailNotificationDto;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.UserEntity;
import freemarker.template.Configuration;
import freemarker.template.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

/**
 * Service pour gérer les notifications par email
 * Supporte les notifications pour :
 * - Création de demande de congé
 * - Approbation de demande
 * - Rejet de demande
 * - Rappels et mises à jour
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final JavaMailSender mailSender;
    @Qualifier("freemarkerConfiguration")
    private final Configuration freemarkerConfig;
    private final JwtService jwtService;

    // Auto-injection pour contourner la limitation @Async sur appels internes (self-invocation).
    @Autowired @Lazy
    private NotificationService self;

    @Value("${app.notification.from.email}")
    private String fromEmail;

    @Value("${app.notification.from.name}")
    private String fromName;

    @Value("${app.notification.enabled}")
    private boolean notificationEnabled;

    @Value("${app.notification.async}")
    private boolean asyncNotification;

    @Value("${app.notification.to.override:}")
    private String toOverride;

    @Value("${app.url:http://localhost:5175}")
    private String appUrl;

    @Value("${app.backend.url:http://localhost:8080}")
    private String backendUrl;

    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Envoie une notification de création de demande de congé
     */
    public void notifyDemandeCreated(UserEntity user, DemandeConge demande) {
        if (!notificationEnabled) {
            log.debug("Notifications désactivées");
            return;
        }
        UserEntity ap = demande.getApprovedBy();
        String approvedBy =
                ap == null
                        ? "—"
                        : (String.valueOf(ap.getPrenom() == null ? "" : ap.getPrenom()).trim()
                           + " "
                           + String.valueOf(ap.getNom() == null ? "" : ap.getNom()).trim()).trim();

        EmailNotificationDto email = EmailNotificationDto.builder()
                .to(user.getEmail())
                .subject("Votre demande de congé a été créée avec succès")
                .templateName("demande-created")
                .build()
                .addVariable("userName", user.getPrenom() + " " + user.getNom())
                .addVariable("typeConge", libelleTypeConge(demande.getTypeConge(), demande.getUser()))
                .addVariable("dateDebut", demande.getDateDebut().format(dateFormatter))
                .addVariable("dateFin", demande.getDateFin().format(dateFormatter))
                .addVariable("nombreJours", demande.getNombreJoursExactOrInt())
                .addVariable("motif", demande.getMotif());
        email.addVariable("approvedBy", approvedBy.isBlank() ? "—" : approvedBy);

        sendEmailNotification(email);
    }

    /**
     * Envoie une notification d'approbation de demande
     */
    public void notifyDemandeApproved(UserEntity user, DemandeConge demande, String approverName) {
        if (!notificationEnabled) {
            return;
        }

        EmailNotificationDto email = EmailNotificationDto.builder()
                .to(user.getEmail())
                .subject("Votre demande de congé a été approuvée")
                .templateName("demande-approved")
                .build()
                .addVariable("userName", user.getPrenom() + " " + user.getNom())
                .addVariable("approverName", approverName)
                .addVariable("typeConge", libelleTypeConge(demande.getTypeConge(), demande.getUser()))
                .addVariable("dateDebut", demande.getDateDebut().format(dateFormatter))
                .addVariable("dateFin", demande.getDateFin().format(dateFormatter))
                .addVariable("nombreJours", demande.getNombreJours());

        sendEmailNotification(email);
    }

    /**
     * Envoie une notification de rejet de demande
     */
    public void notifyDemandeRejected(UserEntity user, DemandeConge demande, String reason) {
        if (!notificationEnabled) {
            return;
        }

        EmailNotificationDto email = EmailNotificationDto.builder()
                .to(user.getEmail())
                .subject("Votre demande de congé a été rejetée")
                .templateName("demande-rejected")
                .build()
                .addVariable("userName", user.getPrenom() + " " + user.getNom())
                .addVariable("typeConge", libelleTypeConge(demande.getTypeConge(), demande.getUser()))
                .addVariable("dateDebut", demande.getDateDebut().format(dateFormatter))
                .addVariable("dateFin", demande.getDateFin().format(dateFormatter))
                .addVariable("reason", reason);

        sendEmailNotification(email);
    }

    /**
     * Envoie une notification de demande en attente à l'approbateur
     */
    public void notifyPendingApproval(UserEntity approver, DemandeConge demande, UserEntity requester) {
        if (!notificationEnabled) {
            return;
        }

        String approveToken = jwtService.generateApprovalToken(demande.getId(), approver.getId(), "APPROVE");
        String rejectToken  = jwtService.generateApprovalToken(demande.getId(), approver.getId(), "REJECT");

        EmailNotificationDto email = EmailNotificationDto.builder()
                .to(approver.getEmail())
                .subject("Nouvelle demande de congé à approuver")
                .templateName("pending-approval")
                .build()
                .addVariable("approverName", approver.getPrenom() + " " + approver.getNom())
                .addVariable("requesterName", requester.getPrenom() + " " + requester.getNom())
                .addVariable("typeConge", libelleTypeConge(demande.getTypeConge(), demande.getUser()))
                .addVariable("dateDebut", demande.getDateDebut().format(dateFormatter))
                .addVariable("dateFin", demande.getDateFin().format(dateFormatter))
                .addVariable("nombreJours", demande.getNombreJours())
                .addVariable("motif", demande.getMotif())
                .addVariable("demandeId", demande.getId())
                .addVariable("approveToken", approveToken)
                .addVariable("rejectToken", rejectToken)
                .addVariable("backendUrl", backendUrl);

        sendEmailNotification(email);
    }

    /**
     * Envoie une notification d'alerte de solde de congés faible
     */
    public void notifyLowLeaveBalance(UserEntity user, int remainingDays) {
        if (!notificationEnabled) {
            return;
        }

        EmailNotificationDto email = EmailNotificationDto.builder()
                .to(user.getEmail())
                .subject("Alerte : Solde de congés faible")
                .templateName("low-balance-alert")
                .build()
                .addVariable("userName", user.getPrenom() + " " + user.getNom())
                .addVariable("remainingDays", remainingDays);

        sendEmailNotification(email);
    }

    /**
     * Envoie une notification à plusieurs destinataires
     */
    public void notifyMultipleRecipients(List<String> recipients, String subject, String templateName, Map<String, Object> variables) {
        if (!notificationEnabled) {
            return;
        }

        for (String recipient : recipients) {
            EmailNotificationDto email = EmailNotificationDto.builder()
                    .to(recipient)
                    .subject(subject)
                    .templateName(templateName)
                    .variables(variables)
                    .build();

            sendEmailNotification(email);
        }
    }

    /**
     * Envoie le mail de notification (synchrone ou asynchrone)
     */
    public void sendEmailNotification(EmailNotificationDto emailDto) {
        if (asyncNotification) {
            self.sendEmailAsync(emailDto); // via proxy Spring pour que @Async soit effectif
        } else {
            sendEmailSync(emailDto);
        }
    }

    /**
     * Envoie un email de manière asynchrone
     */
    @Async("taskExecutor")
    public void sendEmailAsync(EmailNotificationDto emailDto) {
        sendEmailSync(emailDto);
    }

    /**
     * Envoie un email de manière synchrone
     */
    public void sendEmailSync(EmailNotificationDto emailDto) {
        try {
            String htmlContent = buildHtmlContent(emailDto.getTemplateName(), emailDto.getVariables());

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            // Redirection dev : si MAIL_TO_OVERRIDE est défini, tous les emails vont vers cette adresse.
            String effectiveTo = (toOverride != null && !toOverride.isBlank())
                    ? toOverride : emailDto.getTo();

            helper.setFrom(fromEmail, fromName);
            helper.setTo(effectiveTo);
            helper.setSubject(emailDto.getSubject());
            helper.setText(htmlContent, true); // true pour HTML

            if (emailDto.getCc() != null && !emailDto.getCc().isEmpty()) {
                helper.setCc(emailDto.getCc().toArray(new String[0]));
            }

            if (emailDto.getBcc() != null && !emailDto.getBcc().isEmpty()) {
                helper.setBcc(emailDto.getBcc().toArray(new String[0]));
            }

            mailSender.send(message);
            log.info("Email envoyé à {}", emailDto.getTo());

        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Erreur lors de l'envoi du mail à {}", emailDto.getTo(), e);
            // Ne pas lever l'exception pour ne pas interrompre le flux métier
        }
    }

    /**
     * Construit le contenu HTML à partir du template Freemarker
     */
    private String buildHtmlContent(String templateName, Map<String, Object> variables) {
        try {
            Map<String, Object> safe = variables == null ? new HashMap<>() : new HashMap<>(variables);
            safe.putIfAbsent("appUrl", appUrl);
            Template template = freemarkerConfig.getTemplate(templateName + ".ftl");
            StringWriter writer = new StringWriter();
            template.process(safe, writer);
            return writer.toString();
        } catch (Exception e) {
            log.error("Erreur lors de la génération du template {}", templateName, e);
            // Retourner un contenu par défaut si le template n'existe pas
            return getDefaultHtmlContent(templateName, variables);
        }
    }

    public void notifyNewRequestToSuperAdmins(
            List<String> recipients,
            DemandeConge demande,
            UserEntity requester,
            UserEntity approvedByAdmin
    ) {
        if (!notificationEnabled) {
            return;
        }
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        String approver = approvedByAdmin == null
                ? "—"
                : (String.valueOf(approvedByAdmin.getPrenom() == null ? "" : approvedByAdmin.getPrenom()).trim()
                   + " "
                   + String.valueOf(approvedByAdmin.getNom() == null ? "" : approvedByAdmin.getNom()).trim()).trim();
        Map<String, Object> vars = new HashMap<>();
        vars.put("requesterName", (requester.getPrenom() + " " + requester.getNom()).trim());
        vars.put("requesterEmail", requester.getEmail());
        vars.put("typeConge", libelleTypeConge(demande.getTypeConge(), requester));
        vars.put("dateDebut", demande.getDateDebut() != null ? demande.getDateDebut().format(dateFormatter) : "");
        vars.put("dateFin", demande.getDateFin() != null ? demande.getDateFin().format(dateFormatter) : "");
        vars.put("nombreJours", demande.getNombreJours());
        vars.put("motif", demande.getMotif());
        vars.put("approvedBy", approver);
        vars.put("demandeId", demande.getId());

        notifyMultipleRecipients(
                recipients.stream().distinct().toList(),
                "Nouvelle demande de congé créée",
                "superadmin-new-request",
                vars
        );
    }

    private String libelleTypeConge(com.example.conges.entity.TypeConge typeConge, UserEntity user) {
        if (typeConge == null) return "";
        if (typeConge == com.example.conges.entity.TypeConge.COURTE_DUREE) {
            String pays = user != null ? user.getPays() : null;
            if (pays != null) {
                String upper = pays.trim().toUpperCase();
                if ("FR".equals(upper) || upper.contains("FRANC")) {
                    return "RTT";
                }
            }
            return "Sortie courte durée";
        }
        return typeConge.getLibelle();
    }

    /**
     * Contenu HTML par défaut si le template n'existe pas
     */
    private String getDefaultHtmlContent(String templateName, Map<String, Object> variables) {
        return "<html><body>" +
                "<h2>Notification Système</h2>" +
                "<p>Template non trouvé: " + templateName + "</p>" +
                "<p>Variables: " + variables.toString() + "</p>" +
                "</body></html>";
    }

    /**
     * Envoie un email simple sans template
     */
    public void sendSimpleEmail(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            log.info("Email simple envoyé à {}", to);
        } catch (Exception e) {
            log.error("Erreur lors de l'envoi du mail simple à {}", to, e);
        }
    }
}
