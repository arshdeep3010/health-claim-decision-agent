package com.healthclaim.rag.embedding;

/**
 * Formats a float vector as a pgvector literal, e.g. {@code [0.1,0.2,0.3]},
 * for binding into {@code CAST(? AS vector)} statements.
 */
public final class VectorLiterals {

    private VectorLiterals() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
