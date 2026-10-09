package com.enthusia.donors.sandbox.api;

import com.enthusia.donors.sandbox.domain.Domain.*;
import java.util.*;

/** Registered with Bukkit ServicesManager. This test service MUST NOT authorize production rewards. */
public interface DonorProfileService {
    boolean isTestEnvironment();
    Optional<Profile> profile(UUID uuid);
    View snapshot();
}
