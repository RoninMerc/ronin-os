package au.com.roningroup.patrollink;
import org.junit.Test;import static org.junit.Assert.*;
public final class ReportPhotoPolicyTest {
 @Test public void SameOriginImageRoutesAreAccepted(){for(String p:new String[]{"Uploads/photos/report.jpg","Uploads/report.png","AdminCustomer/ImageHandler.ashx?id=12","AdminCustomer/DownloadImage.aspx?id=12"})assertTrue(ReportPhotoPolicy.allowed("https://trlsecurity.silvertracker.net/"+p));}
 @Test public void OtherHostsAndCredentialUrlsAreRejected(){assertFalse(ReportPhotoPolicy.allowed("https://example.org/photo.jpg"));assertFalse(ReportPhotoPolicy.allowed("https://user:secret@trlsecurity.silvertracker.net/photo.jpg"));assertFalse(ReportPhotoPolicy.allowed("http://trlsecurity.silvertracker.net/photo.jpg"));}
 @Test public void MutationModesAndNonImagePagesAreRejected(){assertFalse(ReportPhotoPolicy.allowed("https://trlsecurity.silvertracker.net/DownloadImage.aspx?id=12&action=delete"));assertFalse(ReportPhotoPolicy.allowed("https://trlsecurity.silvertracker.net/DownloadImage.aspx?id=12&action=view&action=save"));assertFalse(ReportPhotoPolicy.allowed("https://trlsecurity.silvertracker.net/AdminCustomer/Issues/Monitor.aspx"));}
 @Test public void DefaultPortAndMalformedLinksAreChecked(){assertTrue(ReportPhotoPolicy.allowed("https://trlsecurity.silvertracker.net:443/photos/p.jpg"));assertFalse(ReportPhotoPolicy.allowed("https://trlsecurity.silvertracker.net:8443/photos/p.jpg"));assertFalse(ReportPhotoPolicy.allowed("not a URL"));}
}
