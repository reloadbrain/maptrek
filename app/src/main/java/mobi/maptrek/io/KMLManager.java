package mobi.maptrek.io;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.InputStream;
import java.io.OutputStream;

import mobi.maptrek.data.Track;
import mobi.maptrek.data.Waypoint;
import mobi.maptrek.data.source.FileDataSource;
import mobi.maptrek.data.style.MarkerStyle;
import mobi.maptrek.data.style.TrackStyle;
import mobi.maptrek.io.kml.KmlParser;
import mobi.maptrek.io.kml.KmlSerializer;
import mobi.maptrek.util.ProgressListener;

public class KMLManager extends Manager {
    public static final String EXTENSION = ".kml";

    @NonNull
    @Override
    public FileDataSource loadData(InputStream inputStream, String filePath) throws Exception {
        FileDataSource dataSource = KmlParser.parse(inputStream);
        int hash = filePath.hashCode() * 31;
        int i = 1;
        // TODO - Generate names if they are missing
        for (Waypoint waypoint : dataSource.waypoints) {
            waypoint._id = 31 * (hash + waypoint.name.hashCode()) + i;
            waypoint.source = dataSource;
            if (waypoint.style.color == 0)
                waypoint.style.color = MarkerStyle.DEFAULT_COLOR;
            i++;
        }
        for (Track track : dataSource.tracks) {
            track.id = 31 * (hash + track.name.hashCode()) + i;
            track.source = dataSource;
            if (track.style.color == 0)
                track.style.color = TrackStyle.DEFAULT_COLOR;
            if (track.style.width == 0f)
                track.style.width = TrackStyle.DEFAULT_WIDTH;
            i++;
        }
        return dataSource;
    }

    @Override
    public void saveData(OutputStream outputStream, FileDataSource source, @Nullable ProgressListener progressListener) throws Exception {
        KmlSerializer.serialize(outputStream, source, progressListener);
    }

    @NonNull
    @Override
    public String getExtension() {
        return EXTENSION;
    }
}
