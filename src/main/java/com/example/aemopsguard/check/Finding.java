package com.example.aemopsguard.check;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** One pass or fail result from a sanity module. */
public record Finding(String module, String subject, boolean passed, String detail) {

    /** Everything a module produced: its findings, and the raw data kept for the snapshot file. */
    public record ModuleResult(List<Finding> findings, ObjectNode snapshot) {
    }
}
