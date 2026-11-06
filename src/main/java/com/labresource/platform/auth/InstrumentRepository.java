package com.labresource.platform.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class InstrumentRepository {
    private static final Logger log = LoggerFactory.getLogger(InstrumentRepository.class);
    private static final List<InstrumentSeed> STARTER_INSTRUMENTS = List.of(
        new InstrumentSeed("Confocal microscope", "Imaging", "Imaging suite", "Available", "High-resolution fluorescence imaging for fixed and live samples."),
        new InstrumentSeed("Inverted fluorescence microscope", "Imaging", "Microscopy room 1", "Available", "Fluorescence and bright-field imaging for cell culture work."),
        new InstrumentSeed("Microcentrifuge", "Sample preparation", "Prep lab bench A", "Available", "Refrigerated microcentrifuge for routine sample preparation."),
        new InstrumentSeed("High-speed centrifuge", "Sample preparation", "Prep lab room 2", "AVAILABLE", "High-speed centrifuge with interchangeable rotor support."),
        new InstrumentSeed("PCR thermocycler", "Molecular biology", "Molecular lab bench B", "Available", "Programmable thermal cycling for PCR workflows."),
        new InstrumentSeed("Real-time PCR system", "Molecular biology", "Molecular lab room 1", "Available", "Quantitative PCR instrument for gene-expression analysis."),
        new InstrumentSeed("Class II biosafety cabinet", "Cell culture", "Cell culture suite", "Available", "Class II cabinet for aseptic cell culture procedures."),
        new InstrumentSeed("CO2 incubator", "Cell culture", "Cell culture suite", "Available", "Temperature- and CO2-controlled incubator for cell cultures."),
        new InstrumentSeed("Autoclave", "Sterilization", "Wash and sterilization room", "UNDER_MAINTENANCE", "Steam sterilizer for compatible laboratory materials."),
        new InstrumentSeed("UV-Vis spectrophotometer", "Analysis", "Analytical lab bench A", "Available", "Absorbance measurements across ultraviolet and visible wavelengths."),
        new InstrumentSeed("Microplate reader", "Analysis", "Analytical lab room 1", "Available", "Multimode microplate reader for absorbance and fluorescence assays."),
        new InstrumentSeed("Analytical balance", "Measurement", "Weighing room", "Available", "Precision balance for small-mass measurements."),
        new InstrumentSeed("-80°C ultra-low freezer", "Storage", "Cold room B", "Available", "Ultra-low temperature storage for long-term sample preservation."),
        new InstrumentSeed("Chemical fume hood", "Safety", "Chemistry lab room 1", "Available", "Ventilated enclosure for approved chemical handling."),
        new InstrumentSeed("Water bath", "Sample preparation", "Prep lab bench C", "Available", "Temperature-controlled bath for routine sample incubation.")
    );

    private final PostgresDatabase database;

    public InstrumentRepository(PostgresDatabase database) { this.database = database; }

    @PostConstruct
    void seedStarterCatalog() {
        String sql = "INSERT INTO instruments (name, category, location, status, description, owner_institution_id) VALUES (?, ?, ?, ?, ?, (SELECT id FROM institutions WHERE name='Local Research Institution')) ON CONFLICT (name) DO NOTHING";
        int inserted = 0;
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (InstrumentSeed instrument : STARTER_INSTRUMENTS) {
                statement.setString(1, instrument.name());
                statement.setString(2, instrument.category());
                statement.setString(3, instrument.location());
                statement.setString(4, canonicalStatus(instrument.status()));
                statement.setString(5, instrument.description());
                inserted += statement.executeUpdate();
            }
            try (PreparedStatement tags = connection.prepareStatement("UPDATE instruments SET asset_tag='LAB-' || LPAD(id::text, 5, '0') WHERE asset_tag IS NULL")) {
                tags.executeUpdate();
            }
            log.info("INSTRUMENT_CATALOG_READY seeded={} starter_catalog_size={}", inserted, STARTER_INSTRUMENTS.size());
        } catch (SQLException exception) {
            log.error("INSTRUMENT_CATALOG_SEED_FAILED reason={}", exception.getMessage());
            throw new IllegalStateException("Could not seed the PostgreSQL instrument catalog.", exception);
        }
    }

    public List<Instrument> findAll() {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                "SELECT i.id,i.asset_tag,i.name,i.category,i.location,i.status,i.description,i.owner_institution_id,ins.name AS owner_institution,i.is_shareable FROM instruments i LEFT JOIN institutions ins ON ins.id=i.owner_institution_id ORDER BY i.category,i.name");
             ResultSet rows = statement.executeQuery()) {
            List<Instrument> instruments = new ArrayList<>();
            while (rows.next()) {
                long owner=rows.getLong("owner_institution_id"); Long ownerId=rows.wasNull()?null:owner;
                instruments.add(new Instrument(rows.getLong("id"), rows.getString("asset_tag"), rows.getString("name"),
                    rows.getString("category"), rows.getString("location"), displayStatus(rows.getString("status")), rows.getString("description"),
                    ownerId,rows.getString("owner_institution"),rows.getBoolean("is_shareable")));
            }
            log.info("INSTRUMENTS_LOADED count={} database=PostgreSQL", instruments.size());
            return instruments;
        } catch (SQLException exception) {
            log.error("INSTRUMENTS_LOAD_FAILED reason={}", exception.getMessage());
            throw new IllegalStateException("Could not load instruments from PostgreSQL.", exception);
        }
    }

    private String displayStatus(String status) {
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case "AVAILABLE" -> "Available";
            case "BOOKED" -> "Booked";
            case "UNDER_MAINTENANCE" -> "Under maintenance";
            case "OUT_OF_SERVICE" -> "Out of service";
            case "RETIRED" -> "Retired";
            default -> status;
        };
    }

    private String canonicalStatus(String status) {
        return switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "available" -> "AVAILABLE";
            case "in use", "booked" -> "BOOKED";
            case "maintenance", "under maintenance", "under_maintenance" -> "UNDER_MAINTENANCE";
            case "out of service", "out_of_service" -> "OUT_OF_SERVICE";
            case "retired" -> "RETIRED";
            default -> status.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private record InstrumentSeed(String name, String category, String location, String status, String description) { }
}
