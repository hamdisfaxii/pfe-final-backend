package com.example.conges.config;

import com.example.conges.dto.ApiErrorResponse;
import javax.persistence.EntityNotFoundException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEntityNotFound(EntityNotFoundException ex) {
        String message = ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Ressource introuvable";
        log.warn("EntityNotFoundException: {}", message);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(false, message));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        String message = ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Accès refusé";
        log.warn("AccessDeniedException: {}", message);
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse(false, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Requête invalide";
        log.warn("IllegalArgumentException: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(false, message));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Opération impossible";
        log.warn("IllegalStateException: {}", message);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(false, message));
    }

    /**
     * Contraintes SQL (FK, unique, NOT NULL) ou schéma désynchronisé — évite un 500 générique.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        Throwable root = ex.getMostSpecificCause();
        log.error("DataIntegrityViolationException", ex);
        String technical = root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage();
        log.warn("Cause: {}", technical);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(false,
                        "Enregistrement refusé par la base (contrainte ou schéma obsolète). "
                                + "Vérifiez les migrations / JPA ddl-auto et les logs serveur."));
    }

    /**
     * Tous les sous-types DataAccessException non déjà gérés (JpaSystemException, BadSqlGrammarException, etc.)
     * — souvent causés par un schéma désynchronisé ou un enum invalide en base.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDataAccess(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        log.error("DataAccessException [{}]: {}", ex.getClass().getSimpleName(),
                cause != null ? cause.getMessage() : ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(false,
                        "Erreur de base de données [" + ex.getClass().getSimpleName() + "]. Consultez les logs serveur."));
    }

    /**
     * Transaction marquée pour rollback par une méthode interne @Transactional avant que l'appelant puisse s'en rendre compte.
     * Retourne 409 plutôt que 500 pour éviter un message opaque.
     */
    @ExceptionHandler(UnexpectedRollbackException.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedRollback(UnexpectedRollbackException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : "Opération impossible : la transaction a été annulée.";
        log.warn("UnexpectedRollbackException: {}", message);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(false, message));
    }

    /**
     * Hibernate enveloppe souvent {@link DataIntegrityViolationException} dans une transaction rollback.
     */
    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<ApiErrorResponse> handleTransactionSystem(TransactionSystemException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof DataIntegrityViolationException dive) {
            return handleDataIntegrity(dive);
        }
        log.error("TransactionSystemException", ex);
        String message = cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()
                ? cause.getMessage()
                : "Erreur de persistance";
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(false, message));
    }

    /**
     * Sécurité API : ne pas exposer de stack interne ; éviter 401 erroné pour NPE / bugs métiers.
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiErrorResponse> handleNullPointer(NullPointerException ex) {
        log.warn("NullPointerException (requête ou données partielles)");
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(false, "Données incomplètes ou indisponibles"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null && root.getMessage() != null && !root.getMessage().isBlank()
                ? root.getMessage()
                : ex.getMessage();
        log.warn("Corps JSON invalide ou inconnu: {}", detail);
        String message = "Requête JSON invalide (champs ou types incorrects).";
        if (detail != null && detail.contains("Unrecognized field")) {
            message = "Champ JSON non reconnu par l’API. Vérifiez les noms de propriétés (ex. commentaire vs motif).";
        }
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(false, message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex
    ) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.debug("Validation échouée: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(false, message));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = ex.getStatus();
        String message = ex.getReason() != null && !ex.getReason().isBlank()
                ? ex.getReason()
                : status.getReasonPhrase();
        log.warn("ResponseStatusException: {} {}", status.value(), message);
        return ResponseEntity
                .status(status)
                .body(new ApiErrorResponse(false, message));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        String message = "Méthode HTTP non supportée";
        log.warn("HttpRequestMethodNotSupportedException: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiErrorResponse(false, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception ex) {
        log.error("Erreur non gérée", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(false, "Une erreur interne est survenue"));
    }
}
