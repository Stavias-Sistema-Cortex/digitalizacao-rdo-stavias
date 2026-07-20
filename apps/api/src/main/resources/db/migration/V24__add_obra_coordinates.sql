-- V24__add_obra_coordinates.sql
ALTER TABLE obra
    ADD COLUMN latitude DECIMAL(10, 7) NULL AFTER rodovia,
    ADD COLUMN longitude DECIMAL(11, 7) NULL AFTER latitude;
