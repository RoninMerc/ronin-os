package au.com.roningroup.patrollink;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.MapTileIndex;
/** Public Esri World Imagery geographic tiles. Tile IDs must be z/y/x, unlike OSM z/x/y. */
final class SatelliteTiles extends XYTileSource {
 SatelliteTiles(){super("EsriSatellite",0,19,256,".jpg",new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"},"© Esri, Maxar, Earthstar Geographics, GIS User Community");}
 @Override public String getTileURLString(long tile){return getBaseUrl()+MapTileIndex.getZoom(tile)+"/"+MapTileIndex.getY(tile)+"/"+MapTileIndex.getX(tile);}
}
