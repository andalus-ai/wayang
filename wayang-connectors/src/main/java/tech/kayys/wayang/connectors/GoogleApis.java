package tech.kayys.wayang.connectors;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Google Calendar and Sheets with an OAuth2 connection ({@code accessToken: ${secret:oauth.CONNECTION}}, see EMAIL.md; request
 * the {@code calendar.events} / {@code calendar.readonly} / {@code spreadsheets} scopes).
 *
 * <p>Connector {@code gcal}: {@code operation} {@code list} (calendarId?, timeMin?, timeMax?, q?, limit?), {@code create}
 * (calendarId?, summary, start, end, description?, location?, attendees?, timeZone?), {@code delete} (calendarId?, eventId).
 * {@code start}/{@code end} are RFC 3339 date-times with an offset, or {@code yyyy-MM-dd} for all-day events. Attendees are
 * <b>not notified</b> unless config {@code notifyAttendees: true}. Optional {@code allowedCalendars} restricts calendar ids.
 *
 * <p>Connector {@code gsheets}: {@code operation} {@code read} (spreadsheetId, range) and {@code append} (spreadsheetId, range,
 * values: list of rows). <b>{@code allowedSpreadsheets} is required.</b> Values are written RAW, so text starting with {@code =}
 * is stored as text and never evaluated as a formula.
 */
public final class GoogleApis {

    private static final Pattern CAL = Pattern.compile("primary|[A-Za-z0-9._%@#-]{1,200}");
    private static final Pattern EVENT = Pattern.compile("[a-v0-9]{5,1024}");
    private static final Pattern SHEET = Pattern.compile("[A-Za-z0-9_-]{20,100}");
    private static final Pattern RANGE = Pattern.compile("[A-Za-z0-9_ !:$'.-]{1,100}");
    private static final Pattern TZ = Pattern.compile("[A-Za-z0-9_/+-]{1,64}");
    private static final int MAX_CELLS = 1000;

    private GoogleApis() {
    }

    public static final class Calendar implements ServiceHandler.Connector {
        private static final String BASE = "https://www.googleapis.com/calendar/v3";
        private final HttpConnector http;

        public Calendar(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("gcal needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            Map<String, String> h = Map.of("Authorization", "Bearer " + Rest.secret(config, "accessToken", "gcal", 10));
            String cal = inputs.get("calendarId") == null ? "primary" : Rest.segment(inputs.get("calendarId"), CAL, "calendarId");
            if (config.get("allowedCalendars") != null) {
                Rest.allowed(config.get("allowedCalendars"), cal, "calendar");
            }
            String path = Rest.base(config, BASE) + "/calendars/" + java.net.URLEncoder.encode(cal, java.nio.charset.StandardCharsets.UTF_8) + "/events";
            String op = config.get("operation") instanceof String o ? o : "";
            switch (op) {
                case "list" -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("singleEvents", "true");
                    q.put("orderBy", "startTime");
                    q.put("maxResults", Rest.limit(inputs.get("limit"), 20, 50));
                    q.put("timeMin", inputs.get("timeMin") == null ? java.time.Instant.now().toString() : instant(inputs.get("timeMin"), "timeMin"));
                    if (inputs.get("timeMax") != null) {
                        q.put("timeMax", instant(inputs.get("timeMax"), "timeMax"));
                    }
                    String text = Rest.text(inputs.get("q"), "q", 200, false);
                    if (text != null) {
                        q.put("q", text);
                    }
                    Map<String, Object> r = Rest.call(http, path, "GET", h, q, null);
                    List<Map<String, Object>> events = new ArrayList<>();
                    if (r.get("items") instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> e) {
                                events.add(summary(e));
                            }
                        }
                    }
                    return Map.of("events", events, "count", events.size());
                }
                case "create" -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("summary", Rest.text(inputs.get("summary"), "summary", 1024, true));
                    String tz = inputs.get("timeZone") instanceof String t ? Rest.segment(t, TZ, "timeZone") : null;
                    body.put("start", when(inputs.get("start"), tz, "start"));
                    body.put("end", when(inputs.get("end"), tz, "end"));
                    String d = Rest.text(inputs.get("description"), "description", 8000, false);
                    if (d != null) {
                        body.put("description", d);
                    }
                    String loc = Rest.text(inputs.get("location"), "location", 500, false);
                    if (loc != null) {
                        body.put("location", loc);
                    }
                    if (inputs.get("attendees") instanceof List<?> l) {
                        if (l.size() > 50) {
                            throw new ServiceHandler.ConnectorException("More than 50 attendees", false);
                        }
                        List<Map<String, String>> at = new ArrayList<>();
                        for (Object o : l) {
                            String a = o instanceof String s ? Gmail.address(MailWire.clean(s, "attendee")) : null;
                            if (a == null) {
                                throw new ServiceHandler.ConnectorException("Invalid attendee address", false);
                            }
                            at.add(Map.of("email", a));
                        }
                        body.put("attendees", at);
                    }
                    Map<String, Object> q = Map.of("sendUpdates", Boolean.TRUE.equals(config.get("notifyAttendees")) ? "all" : "none");
                    return summary(Rest.call(http, path, "POST", h, q, body));
                }
                case "delete" -> {
                    String id = Rest.segment(inputs.get("eventId"), EVENT, "eventId");
                    Rest.call(http, path + "/" + id, "DELETE", h, Map.of("sendUpdates", "none"), null);
                    return Map.of("deleted", true);
                }
                default -> throw new ServiceHandler.ConnectorException("gcal 'operation' must be list, create or delete", false);
            }
        }

        private static String instant(Object v, String what) throws ServiceHandler.ConnectorException {
            try {
                return OffsetDateTime.parse(String.valueOf(v)).toInstant().toString();
            } catch (DateTimeParseException e) {
                throw new ServiceHandler.ConnectorException(what + " must be RFC 3339 with an offset, e.g. 2026-10-26T09:00:00+07:00", false);
            }
        }

        private static Map<String, Object> when(Object v, String tz, String what) throws ServiceHandler.ConnectorException {
            String s = v instanceof String x ? x : "";
            Map<String, Object> m = new LinkedHashMap<>();
            try {
                if (s.length() == 10) {
                    m.put("date", LocalDate.parse(s).toString());
                } else {
                    m.put("dateTime", OffsetDateTime.parse(s).toString());
                    if (tz != null) {
                        m.put("timeZone", tz);
                    }
                }
            } catch (DateTimeParseException e) {
                throw new ServiceHandler.ConnectorException(what + " must be yyyy-MM-dd or RFC 3339 with an offset", false);
            }
            return m;
        }

        private static Map<String, Object> summary(Map<?, ?> e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", e.get("id"));
            out.put("summary", e.get("summary"));
            out.put("start", e.get("start") instanceof Map<?, ?> s ? (s.get("dateTime") != null ? s.get("dateTime") : s.get("date")) : null);
            out.put("end", e.get("end") instanceof Map<?, ?> s ? (s.get("dateTime") != null ? s.get("dateTime") : s.get("date")) : null);
            out.put("location", e.get("location"));
            out.put("status", e.get("status"));
            out.put("url", e.get("htmlLink"));
            return out;
        }
    }

    public static final class Sheets implements ServiceHandler.Connector {
        private static final String BASE = "https://sheets.googleapis.com/v4";
        private final HttpConnector http;

        public Sheets(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("gsheets needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            Map<String, String> h = Map.of("Authorization", "Bearer " + Rest.secret(config, "accessToken", "gsheets", 10));
            String id = Rest.segment(inputs.get("spreadsheetId"), SHEET, "spreadsheetId");
            Rest.allowed(config.get("allowedSpreadsheets"), id, "spreadsheet");
            String range = Rest.segment(inputs.get("range"), RANGE, "range");
            String url = Rest.base(config, BASE) + "/spreadsheets/" + id + "/values/"
                    + java.net.URLEncoder.encode(range, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String op = config.get("operation") instanceof String o ? o : "";
            switch (op) {
                case "read" -> {
                    Map<String, Object> r = Rest.call(http, url, "GET", h, Map.of("majorDimension", "ROWS"), null);
                    List<?> values = r.get("values") instanceof List<?> l ? l : List.of();
                    return Map.of("values", values, "rows", values.size(), "range", String.valueOf(r.getOrDefault("range", range)));
                }
                case "append" -> {
                    if (!(inputs.get("values") instanceof List<?> rows) || rows.isEmpty()) {
                        throw new ServiceHandler.ConnectorException("gsheets append needs 'values' as a list of rows", false);
                    }
                    int cells = 0;
                    List<List<Object>> clean = new ArrayList<>();
                    for (Object r : rows) {
                        if (!(r instanceof List<?> row)) {
                            throw new ServiceHandler.ConnectorException("Each row in 'values' must be a list", false);
                        }
                        List<Object> cr = new ArrayList<>();
                        for (Object c : row) {
                            if (++cells > MAX_CELLS) {
                                throw new ServiceHandler.ConnectorException("More than " + MAX_CELLS + " cells", false);
                            }
                            if (c instanceof String s && s.length() > 50_000) {
                                throw new ServiceHandler.ConnectorException("A cell is longer than 50000 characters", false);
                            }
                            cr.add(c == null || c instanceof String || c instanceof Number || c instanceof Boolean ? (c == null ? "" : c) : String.valueOf(c));
                        }
                        clean.add(cr);
                    }
                    Map<String, Object> q = Map.of("valueInputOption", "RAW", "insertDataOption", "INSERT_ROWS");
                    Map<String, Object> r = Rest.call(http, url + ":append", "POST", h, q, Map.of("majorDimension", "ROWS", "values", clean));
                    Object upd = r.get("updates") instanceof Map<?, ?> u ? u.get("updatedRange") : null;
                    return Map.of("rows", clean.size(), "updatedRange", upd == null ? "" : String.valueOf(upd));
                }
                default -> throw new ServiceHandler.ConnectorException("gsheets 'operation' must be read or append", false);
            }
        }
    }
}
