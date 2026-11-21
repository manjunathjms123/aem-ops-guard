package com.example.aemopsguard.drift;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one environment looked like when it was read.
 *
 * @param name            environment name, for example "qa"
 * @param bundleStates    symbolic name to bundle state, for example "Active"
 * @param configuredServices IDs of services that have a configuration
 * @param groups          authorizable IDs of the groups under /home/groups
 * @param userCount       users under /home/users, or -1 when unavailable
 * @param groupCount      groups under /home/groups, or -1 when unavailable
 * @param notes           anything that could not be read, which makes the comparison incomplete
 */
public record EnvironmentSnapshot(
        String name,
        Map<String, String> bundleStates,
        Set<String> configuredServices,
        Set<String> groups,
        long userCount,
        long groupCount,
        List<String> notes) {
}
