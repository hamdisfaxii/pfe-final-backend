package com.example.conges.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configuration de la source de données Dolibarr.
 * Quand la DB Dolibarr n'est pas configurée (host vide), on utilise H2 en mémoire
 * comme fallback : les requêtes SQL Dolibarr échoueront proprement (table introuvable)
 * et seront interceptées par les try-catch dans DolibarrService.
 */
@Configuration
public class DolibarrDbConfig {

    @Bean("dolibarrJdbcTemplate")
    public JdbcTemplate dolibarrJdbcTemplate(
            @Value("${dolibarr.db.host:}") String host,
            @Value("${dolibarr.db.port:3306}") int port,
            @Value("${dolibarr.db.name:}") String dbName,
            @Value("${dolibarr.db.user:}") String user,
            @Value("${dolibarr.db.password:}") String password
    ) {
        if (host == null || host.isBlank() || dbName == null || dbName.isBlank()) {
            // Fallback H2 en mémoire — aucune connexion réseau requise
            DataSource h2 = DataSourceBuilder.create()
                    .driverClassName("org.h2.Driver")
                    .url("jdbc:h2:mem:dolibarr_fallback;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false")
                    .username("sa")
                    .password("")
                    .build();
            return new JdbcTemplate(h2);
        }

        String url = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        DataSource ds = DataSourceBuilder.create()
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .url(url)
                .username(user)
                .password(password)
                .build();
        return new JdbcTemplate(ds);
    }
}
