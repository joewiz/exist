/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.xquery.ft;

import org.exist.xquery.util.ExpressionDumper;

import java.util.ArrayList;
import java.util.List;

/**
 * W3C XQFT 3.0 — FTMatchOptions.
 *
 * Collects match options specified via "using" clauses on an FTPrimaryWithOptions.
 * Each option overrides the inherited default from the static context.
 */
public class FTMatchOptions {

    public enum CaseMode { SENSITIVE, INSENSITIVE, LOWERCASE, UPPERCASE }
    public enum DiacriticsMode { SENSITIVE, INSENSITIVE }

    private CaseMode caseMode;
    private DiacriticsMode diacriticsMode;
    private Boolean stemming;       // null = not specified
    private Boolean wildcards;      // null = not specified
    private String language;        // BCP 47 tag, null = not specified
    private Boolean noThesaurus;
    private final List<String> thesaurusURIs = new ArrayList<>();
    private Boolean noStopWords;
    private final List<String> stopWordURIs = new ArrayList<>();
    private final List<String> inlineStopWords = new ArrayList<>();

    public CaseMode getCaseMode() { return caseMode; }
    public void setCaseMode(final CaseMode caseMode) { this.caseMode = caseMode; }

    public DiacriticsMode getDiacriticsMode() { return diacriticsMode; }
    public void setDiacriticsMode(final DiacriticsMode diacriticsMode) { this.diacriticsMode = diacriticsMode; }

    public Boolean getStemming() { return stemming; }
    public void setStemming(final Boolean stemming) { this.stemming = stemming; }

    public Boolean getWildcards() { return wildcards; }
    public void setWildcards(final Boolean wildcards) { this.wildcards = wildcards; }

    public String getLanguage() { return language; }
    public void setLanguage(final String language) { this.language = language; }

    public Boolean getNoThesaurus() { return noThesaurus; }
    public void setNoThesaurus(final Boolean noThesaurus) { this.noThesaurus = noThesaurus; }
    public List<String> getThesaurusURIs() { return thesaurusURIs; }

    public Boolean getNoStopWords() { return noStopWords; }
    public void setNoStopWords(final Boolean noStopWords) { this.noStopWords = noStopWords; }
    public List<String> getStopWordURIs() { return stopWordURIs; }
    public List<String> getInlineStopWords() { return inlineStopWords; }

    public void dump(final ExpressionDumper dumper) {
        if (caseMode != null) {
            dumper.display(" using case ").display(caseMode.name().toLowerCase());
        }
        if (diacriticsMode != null) {
            dumper.display(" using diacritics ").display(diacriticsMode.name().toLowerCase());
        }
        if (stemming != null) {
            dumper.display(stemming ? " using stemming" : " using no stemming");
        }
        if (wildcards != null) {
            dumper.display(wildcards ? " using wildcards" : " using no wildcards");
        }
        if (language != null) {
            dumper.display(" using language \"").display(language).display("\"");
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        if (caseMode != null) {
            sb.append(" using case ").append(caseMode.name().toLowerCase());
        }
        if (diacriticsMode != null) {
            sb.append(" using diacritics ").append(diacriticsMode.name().toLowerCase());
        }
        if (stemming != null) {
            sb.append(stemming ? " using stemming" : " using no stemming");
        }
        if (wildcards != null) {
            sb.append(wildcards ? " using wildcards" : " using no wildcards");
        }
        if (language != null) {
            sb.append(" using language \"").append(language).append("\"");
        }
        return sb.toString();
    }
}
