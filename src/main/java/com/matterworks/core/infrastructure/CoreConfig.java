package com.matterworks.core.infrastructure;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Gestore della configurazione esterna.
 * Legge il file 'matterworks.properties' dalla root del server.
 * Permette di cambiare i valori di gioco senza ricompilare.
 */
public class CoreConfig {

    private static final Properties props = new Properties();

    // Carica il file all'avvio
    public static void load() {
        try (FileInputStream in = new FileInputStream("matterworks.properties")) {
            props.load(in);
            System.out.println("⚙️ Configurazione caricata da matterworks.properties");
        } catch (IOException e) {
            System.out.println("⚠️ matterworks.properties non trovato. Uso valori di DEFAULT.");
        }
    }

    /**
     * Recupera un intero dalla config, o usa il default se manca.
     */
    public static int getInt(String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            System.err.println("❌ Errore config per " + key + ": " + val + " non è un numero.");
            return defaultValue;
        }
    }
}