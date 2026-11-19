package sereneseasons.api.season;

/** Stand-in: the four methods of Serene Seasons' ISeasonState that the bridge calls. */
public interface ISeasonState {
    int getDayDuration();

    int getCycleDuration();

    int getDay();

    Season getSeason();
}
