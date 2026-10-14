package au.com.roningroup.patrollink;
import org.junit.Test;import static org.junit.Assert.*;import java.time.ZoneId;
public final class CaptureTimestampTest {
 @Test public void ExplicitAustralianReportDateUsesConfiguredZone(){assertEquals("2026-07-13T20:00:00Z",CaptureTimestamp.parse("","14/07/2026 06:00",ZoneId.of("Australia/Brisbane")));}
 @Test public void DoesNotInventMissingYearOrTimezone(){assertNull(CaptureTimestamp.parse("Tue 14/07 6:00 AM","",ZoneId.of("Australia/Brisbane")));assertNull(CaptureTimestamp.parse("","14/07/2026 06:00",null));}
 @Test public void InvalidDatesAreNotNormalisedIntoDifferentDays(){assertNull(CaptureTimestamp.parse("31/02/2026 06:00","",ZoneId.of("Australia/Brisbane")));assertNull(CaptureTimestamp.parse("14/07/2026 99:00","",ZoneId.of("Australia/Brisbane")));}
 @Test public void IsoOffsetsAndExplicitFallbackAreRetained(){assertEquals("2026-07-13T20:00:00Z",CaptureTimestamp.parse("2026-07-14T06:00:00+10:00","",null));assertEquals("2026-07-13T20:00:00Z",CaptureTimestamp.parse("invalid 2026 date","14/07/2026 6:00 AM",ZoneId.of("Australia/Brisbane")));}
}
