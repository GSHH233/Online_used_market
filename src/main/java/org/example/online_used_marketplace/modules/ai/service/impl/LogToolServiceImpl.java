package org.example.online_used_marketplace.modules.ai.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.online_used_marketplace.modules.ai.dto.LogQueryRequestDTO;
import org.example.online_used_marketplace.modules.ai.service.LogToolService;
import org.example.online_used_marketplace.modules.ai.vo.ToolQueryResultVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 日志查询工具（真实文件读取实现）
 */
@Service
@Slf4j
public class LogToolServiceImpl implements LogToolService {

    @Value("${ai.tools.log.file-path:logs/app.log}")
    private String logFilePath;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public ToolQueryResultVO queryByTraceId(LogQueryRequestDTO request) {
        long start = System.currentTimeMillis();
        String traceId = request.getTraceId();
        Integer limit = normalizeLimit(request.getLimit());

        if (!StringUtils.hasText(traceId)) {
            return buildEmpty(traceId, request.getService(), limit, "traceId为空，未执行查询", start);
        }

        Path path = Paths.get(logFilePath);
        if (!Files.exists(path)) {
            log.warn("log file not found, path={}", path.toAbsolutePath());
            return buildEmpty(traceId, request.getService(), limit, "日志文件不存在", start);
        }

        List<String> filtered = new ArrayList<>();
        LocalDateTime fromTime = buildFromTime(request.getWindowMinutes());

        try (Stream<String> lines = Files.lines(path)) {
            lines.filter(line -> line.contains(traceId))
                    .filter(line -> matchService(line, request.getService()))
                    .filter(line -> matchWindow(line, fromTime))
                    .forEach(filtered::add);
        } catch (IOException e) {
            log.error("read log file failed, path={}", path.toAbsolutePath(), e);
            return buildEmpty(traceId, request.getService(), limit, "读取日志失败", start);
        }

        Collections.reverse(filtered);

        List<String> sliced = filtered;
        boolean truncated = false;
        if (filtered.size() > limit) {
            sliced = filtered.subList(0, limit);
            truncated = true;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        long lineNo = 1L;
        for (String line : sliced) {
            rows.add(Map.of(
                    "lineNo", lineNo++,
                    "content", line
            ));
        }

        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool("log_query");
        vo.setTarget("traceId=" + traceId);
        vo.setLimit(limit);
        vo.setTotal((long) filtered.size());
        vo.setTruncated(truncated);
        vo.setElapsedMs(System.currentTimeMillis() - start);
        vo.setColumns(List.of("lineNo", "content"));
        vo.setRows(rows);
        vo.setSummary("日志查询完成");
        return vo;
    }

    private boolean matchService(String line, String service) {
        if (!StringUtils.hasText(service)) {
            return true;
        }
        return line.contains("[" + service + "]") || line.contains("service=" + service);
    }

    private LocalDateTime buildFromTime(Integer windowMinutes) {
        int minutes = (windowMinutes == null || windowMinutes <= 0) ? 0 : windowMinutes;
        if (minutes == 0) {
            return null;
        }
        return LocalDateTime.now().minusMinutes(minutes);
    }

    private boolean matchWindow(String line, LocalDateTime fromTime) {
        if (fromTime == null) {
            return true;
        }
        LocalDateTime lineTs = extractLineTime(line);
        if (lineTs == null) {
            return true;
        }
        return !lineTs.isBefore(fromTime);
    }

    private LocalDateTime extractLineTime(String line) {
        if (!StringUtils.hasText(line) || line.length() < 19) {
            return null;
        }
        String prefix = line.substring(0, 19);
        try {
            return LocalDateTime.parse(prefix, TS_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private ToolQueryResultVO buildEmpty(String traceId, String service, Integer limit, String summary, long startMs) {
        ToolQueryResultVO vo = new ToolQueryResultVO();
        vo.setTool("log_query");
        vo.setTarget(StringUtils.hasText(traceId) ? "traceId=" + traceId : "traceId=EMPTY");
        vo.setLimit(limit);
        vo.setTotal(0L);
        vo.setTruncated(false);
        vo.setElapsedMs(System.currentTimeMillis() - startMs);
        vo.setColumns(List.of("lineNo", "content"));
        vo.setRows(List.of());
        vo.setSummary(summary + (StringUtils.hasText(service) ? ("; service=" + service) : ""));
        return vo;
    }

    private Integer normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 500);
    }
}
