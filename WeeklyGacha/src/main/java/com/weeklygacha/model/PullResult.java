package com.weeklygacha.model;

import java.util.List;

public record PullResult(boolean success, List<PullEntry> entries, double cost) {

    public static final PullResult FAIL = new PullResult(false, List.of(), 0);
}
