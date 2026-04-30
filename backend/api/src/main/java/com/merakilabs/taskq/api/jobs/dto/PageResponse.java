package com.merakilabs.taskq.api.jobs.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, int limit, int offset, long total) {}
