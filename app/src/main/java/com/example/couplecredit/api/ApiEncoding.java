package com.example.couplecredit.api;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public final class ApiEncoding {
    private ApiEncoding() {}
    public static String query(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError("UTF-8 must be supported", impossible);
        }
    }
}
