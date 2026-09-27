package com.course.vsearch.service.correct;

import java.util.List;

public record CorrectionResult(String original, String corrected, List<CorrectionTrace> traces) {

    public boolean changed() {
        return !original.equals(corrected);
    }
}
