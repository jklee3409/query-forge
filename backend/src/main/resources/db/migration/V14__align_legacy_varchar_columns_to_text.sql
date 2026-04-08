ALTER TABLE corpus_sources
    ALTER COLUMN source_id TYPE TEXT,
    ALTER COLUMN source_type TYPE TEXT,
    ALTER COLUMN product_name TYPE TEXT,
    ALTER COLUMN source_name TYPE TEXT,
    ALTER COLUMN base_url TYPE TEXT,
    ALTER COLUMN default_version TYPE TEXT;

ALTER TABLE corpus_documents
    ALTER COLUMN document_id TYPE TEXT,
    ALTER COLUMN source_id TYPE TEXT,
    ALTER COLUMN product_name TYPE TEXT,
    ALTER COLUMN version_label TYPE TEXT,
    ALTER COLUMN canonical_url TYPE TEXT,
    ALTER COLUMN title TYPE TEXT,
    ALTER COLUMN section_path_text TYPE TEXT,
    ALTER COLUMN raw_checksum TYPE TEXT,
    ALTER COLUMN cleaned_checksum TYPE TEXT,
    ALTER COLUMN raw_text TYPE TEXT,
    ALTER COLUMN cleaned_text TYPE TEXT,
    ALTER COLUMN language_code TYPE TEXT,
    ALTER COLUMN content_type TYPE TEXT;

ALTER TABLE corpus_glossary_terms
    ALTER COLUMN canonical_form TYPE TEXT,
    ALTER COLUMN normalized_form TYPE TEXT,
    ALTER COLUMN term_type TYPE TEXT,
    ALTER COLUMN description_short TYPE TEXT,
    ALTER COLUMN first_seen_document_id TYPE TEXT,
    ALTER COLUMN first_seen_chunk_id TYPE TEXT;
