package com.nullyard.reader;

import java.util.List;

interface OnlineProvider {
    String id();
    String name();
    String attributionText();
    String attributionUrl();

    List<Book> loadCatalog() throws Exception;
    Download resolveDownload(Book book) throws Exception;

    final class Book {
        final String providerId;
        final String providerName;
        final String title;
        final String author;
        final String detailUrl;
        final String sourceUrl;
        final String coverUrl;

        Book(String providerId, String providerName, String title, String author,
             String detailUrl, String sourceUrl, String coverUrl) {
            this.providerId = providerId;
            this.providerName = providerName;
            this.title = title;
            this.author = author;
            this.detailUrl = detailUrl;
            this.sourceUrl = sourceUrl;
            this.coverUrl = coverUrl;
        }
    }

    final class Download {
        final String epubUrl;
        final String coverUrl;

        Download(String epubUrl, String coverUrl) {
            this.epubUrl = epubUrl;
            this.coverUrl = coverUrl;
        }
    }
}
