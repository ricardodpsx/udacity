package com.google.devrel.training.conference.spi;

import static com.google.devrel.training.conference.service.OfyService.factory;
import static com.google.devrel.training.conference.service.OfyService.ofy;

import com.google.api.server.spi.config.Api;
import com.google.api.server.spi.config.ApiMethod;
import com.google.api.server.spi.config.ApiMethod.HttpMethod;
import com.google.api.server.spi.response.ConflictException;
import com.google.api.server.spi.response.ForbiddenException;
import com.google.api.server.spi.response.NotFoundException;
import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.memcache.MemcacheService;
import com.google.appengine.api.memcache.MemcacheServiceFactory;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.users.User;
import com.google.devrel.training.conference.Constants;
import com.google.devrel.training.conference.domain.*;
import com.google.devrel.training.conference.form.ConferenceForm;
import com.google.devrel.training.conference.form.ConferenceQueryForm;
import com.google.devrel.training.conference.form.ProfileForm;
import com.google.devrel.training.conference.form.ProfileForm.TeeShirtSize;
import com.google.devrel.training.conference.form.SessionForm;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.cmd.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import javax.inject.Named;

/**
 * Defines conference APIs.
 */

@Api(
        name = "conference",
        version = "v1",
        scopes = { Constants.EMAIL_SCOPE },
        clientIds = { Constants.WEB_CLIENT_ID, Constants.ANDROID_CLIENT_ID,
                Constants.API_EXPLORER_CLIENT_ID},
        audiences = {Constants.ANDROID_AUDIENCE},
        description = "Conference Central API for creating and querying conferences," +
                " and for creating and getting user Profiles"
)
public class ConferenceApi {

    private static final Logger LOG = Logger.getLogger(ConferenceApi.class.getName());

    private static String extractDefaultDisplayNameFromEmail(String email) {
        return email == null ? null : email.substring(0, email.indexOf("@"));
    }

    private static Profile getProfileFromUser(User user, String userId) {
        // First fetch it from the datastore.
        Profile profile = ofy().load().key(
                Key.create(Profile.class, userId)).now();
        if (profile == null) {
            // Create a new Profile if not exist.
            String email = user.getEmail();
            profile = new Profile(userId,
                    extractDefaultDisplayNameFromEmail(email), email, TeeShirtSize.NOT_SPECIFIED);
        }
        return profile;
    }

    /**
     * This is an ugly workaround for null userId for Android clients.
     *
     * @param user A User object injected by the cloud endpoints.
     * @return the App Engine userId for the user.
     */
    private static String getUserId(User user) {
        String userId = user.getUserId();
        if (userId == null) {
            LOG.info("userId is null, so trying to obtain it from the datastore.");
            AppEngineUser appEngineUser = new AppEngineUser(user);
            ofy().save().entity(appEngineUser).now();
            // Begin new session for not using session cache.
            Objectify objectify = ofy().factory().begin();
            AppEngineUser savedUser = objectify.load().key(appEngineUser.getKey()).now();
            userId = savedUser.getUser().getUserId();
            LOG.info("Obtained the userId: " + userId);
        }
        return userId;
    }

    /**
     * Just a wrapper for Boolean.
     */
    public static class WrappedBoolean {

        private final Boolean result;

        public WrappedBoolean(Boolean result) {
            this.result = result;
        }

        public Boolean getResult() {
            return result;
        }
    }

    /**
     * A wrapper class that can embrace a generic result or some kind of exception.
     *
     * Use this wrapper class for the return type of objectify transaction.
     * <pre>
     * {@code
     * // The transaction that returns Conference object.
     * TxResult<Conference> result = ofy().transact(new Work<TxResult<Conference>>() {
     *     public TxResult<Conference> run() {
     *         // Code here.
     *         // To throw 404
     *         return new TxResult<>(new NotFoundException("No such conference"));
     *         // To return a conference.
     *         Conference conference = somehow.getConference();
     *         return new TxResult<>(conference);
     *     }
     * }
     * // Actually the NotFoundException will be thrown here.
     * return result.getResult();
     * </pre>
     *
     * @param <ResultType> The type of the actual return object.
     */
    private static class TxResult<ResultType> {

        private ResultType result;

        private Throwable exception;

        private TxResult(ResultType result) {
            this.result = result;
        }

        private TxResult(Throwable exception) {
            if (exception instanceof NotFoundException ||
                    exception instanceof ForbiddenException ||
                    exception instanceof ConflictException) {
                this.exception = exception;
            } else {
                throw new IllegalArgumentException("Exception not supported.");
            }
        }

        private ResultType getResult() throws NotFoundException, ForbiddenException, ConflictException {
            if (exception instanceof NotFoundException) {
                throw (NotFoundException) exception;
            }
            if (exception instanceof ForbiddenException) {
                throw (ForbiddenException) exception;
            }
            if (exception instanceof ConflictException) {
                throw (ConflictException) exception;
            }
            return result;
        }
    }

    /**
     * Returns a Profile object associated with the given user object. The cloud endpoints system
     * automatically inject the User object.
     *
     * @param user A User object injected by the cloud endpoints.
     * @return Profile object.
     * @throws UnauthorizedException when the User object is null.
     */
    @ApiMethod(name = "getProfile", path = "profile", httpMethod = HttpMethod.GET)
    public Profile getProfile(final User user) throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        return ofy().load().key(Key.create(Profile.class, getUserId(user))).now();
    }




    /**
     * Creates or updates a Profile object associated with the given user object.
     *
     * @param user A User object injected by the cloud endpoints.
     * @param profileForm A ProfileForm object sent from the client form.
     * @return Profile object just created.
     * @throws UnauthorizedException when the User object is null.
     */
    @ApiMethod(name = "saveProfile", path = "profile", httpMethod = HttpMethod.POST)
    public Profile saveProfile(final User user, final ProfileForm profileForm)
            throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        String displayName = profileForm.getDisplayName();
        TeeShirtSize teeShirtSize = profileForm.getTeeShirtSize();

        Profile profile = ofy().load().key(Key.create(Profile.class, getUserId(user))).now();
        if (profile == null) {
            // Populate displayName and teeShirtSize with the default values if null.
            if (displayName == null) {
                displayName = extractDefaultDisplayNameFromEmail(user.getEmail());
            }
            if (teeShirtSize == null) {
                teeShirtSize = TeeShirtSize.NOT_SPECIFIED;
            }
            profile = new Profile(getUserId(user), displayName, user.getEmail(), teeShirtSize);
        } else {
            profile.update(displayName, teeShirtSize);
        }
        ofy().save().entity(profile).now();
        return profile;
    }

    /**
     * Creates a new Conference object and stores it to the datastore.
     *
     * @param user A user who invokes this method, null when the user is not signed in.
     * @param conferenceForm A ConferenceForm object representing user's inputs.
     * @return A newly created Conference Object.
     * @throws UnauthorizedException when the user is not signed in.
     */
    @ApiMethod(name = "createConference", path = "conference", httpMethod = HttpMethod.POST)
    public Conference createConference(final User user, final ConferenceForm conferenceForm)
        throws UnauthorizedException {
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        // Allocate Id first, in order to make the transaction idempotent.
        Key<Profile> profileKey = Key.create(Profile.class, getUserId(user));
        final Key<Conference> conferenceKey = factory().allocateId(profileKey, Conference.class);
        final long conferenceId = conferenceKey.getId();
        final Queue queue = QueueFactory.getDefaultQueue();
        final String userId = getUserId(user);
        // Start a transaction.
        Conference conference = ofy().transact(new Work<Conference>() {
            @Override
            public Conference run() {
                // Fetch user's Profile.
                Profile profile = getProfileFromUser(user, userId);
                Conference conference = new Conference(conferenceId, userId, conferenceForm);
                // Save Conference and Profile.
                ofy().save().entities(conference, profile).now();
                queue.add(ofy().getTransaction(),
                        TaskOptions.Builder.withUrl("/tasks/send_confirmation_email")
                        .param("email", profile.getMainEmail())
                        .param("conferenceInfo", conference.toString()));
                return conference;
            }
        });
        return conference;
    }

    /**
     * Updates the existing Conference with the given conferenceId.
     *
     * @param user A user who invokes this method, null when the user is not signed in.
     * @param conferenceForm A ConferenceForm object representing user's inputs.
     * @param websafeConferenceKey The String representation of the Conference key.
     * @return Updated Conference object.
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     * @throws ForbiddenException when the user is not the owner of the Conference.
     */
    @ApiMethod(
            name = "updateConference",
            path = "conference/{websafeConferenceKey}",
            httpMethod = HttpMethod.PUT
    )
    public Conference updateConference(final User user, final ConferenceForm conferenceForm,
                                       @Named("websafeConferenceKey")
                                       final String websafeConferenceKey)
            throws UnauthorizedException, NotFoundException, ForbiddenException, ConflictException {
        // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        final String userId = getUserId(user);
        // Update the conference with the conferenceForm sent from the client.
        // Need a transaction because we need to safely preserve the number of allocated seats.
        TxResult<Conference> result = ofy().transact(new Work<TxResult<Conference>>() {
            @Override
            public TxResult<Conference> run() {
                // If there is no Conference with the id, throw a 404 error.
                Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
                Conference conference = ofy().load().key(conferenceKey).now();
                if (conference == null) {
                    return new TxResult<>(
                            new NotFoundException("No Conference found with the key: "
                                    + websafeConferenceKey));
                }
                // If the user is not the owner, throw a 403 error.
                Profile profile = ofy().load().key(Key.create(Profile.class, userId)).now();
                if (profile == null ||
                        !conference.getOrganizerUserId().equals(userId)) {
                    return new TxResult<>(
                            new ForbiddenException("Only the owner can update the conference."));
                }
                conference.updateWithConferenceForm(conferenceForm);
                ofy().save().entity(conference).now();
                return new TxResult<>(conference);
            }
        });
        // NotFoundException or ForbiddenException is actually thrown here.
        return result.getResult();
    }

    @ApiMethod(
            name = "getAnnouncement",
            path = "announcement",
            httpMethod = HttpMethod.GET
    )
    public Announcement getAnnouncement() {
        MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();
        Object message = memcacheService.get(Constants.MEMCACHE_ANNOUNCEMENTS_KEY);
        if (message != null) {
            return new Announcement(message.toString());
        }
        return null;
    }

    /**
     * Returns a Conference object with the given conferenceId.
     *
     * @param websafeConferenceKey The String representation of the Conference Key.
     * @return a Conference object with the given conferenceId.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "getConference",
            path = "conference/{websafeConferenceKey}",
            httpMethod = HttpMethod.GET
    )
    public Conference getConference(
            @Named("websafeConferenceKey") final String websafeConferenceKey)
            throws NotFoundException {
        Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
        Conference conference = ofy().load().key(conferenceKey).now();
        if (conference == null) {
            throw new NotFoundException("No Conference found with key: " + websafeConferenceKey);
        }
        return conference;
    }

    /**
     * Returns a collection of Conference Object that the user is going to attend.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @return a Collection of Conferences that the user is going to attend.
     * @throws UnauthorizedException when the User object is null.
     */
    @ApiMethod(
            name = "getConferencesToAttend",
            path = "getConferencesToAttend",
            httpMethod = HttpMethod.GET
    )
    public Collection<Conference> getConferencesToAttend(final User user)
            throws UnauthorizedException, NotFoundException {
        // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        Profile profile = ofy().load().key(Key.create(Profile.class, getUserId(user))).now();
        if (profile == null) {
            throw new NotFoundException("Profile doesn't exist.");
        }
        List<String> keyStringsToAttend = profile.getConferenceKeysToAttend();
        List<Key<Conference>> keysToAttend = new ArrayList<>();
        for (String keyString : keyStringsToAttend) {
            keysToAttend.add(Key.<Conference>create(keyString));
        }
        return ofy().load().keys(keysToAttend).values();
    }

    /**
     * Queries against the datastore with the given filters and returns the result.
     *
     * Normally this kind of method is supposed to get invoked by a GET HTTP method,
     * but we do it with POST, in order to receive conferenceQueryForm Object via the POST body.
     *
     * @param conferenceQueryForm A form object representing the query.
     * @return A List of Conferences that match the query.
     */
    @ApiMethod(
            name = "queryConferences",
            path = "queryConferences",
            httpMethod = HttpMethod.POST
    )
    public List<Conference> queryConferences(ConferenceQueryForm conferenceQueryForm) {
        Iterable<Conference> conferenceIterable = conferenceQueryForm.getQuery();
        List<Conference> result = new ArrayList<>(0);
        List<Key<Profile>> organizersKeyList = new ArrayList<>(0);
        for (Conference conference : conferenceIterable) {
            organizersKeyList.add(Key.create(Profile.class, conference.getOrganizerUserId()));
            result.add(conference);
        }
        // To avoid separate datastore gets for each Conference, pre-fetch the Profiles.
        ofy().load().keys(organizersKeyList);
        return result;
    }

    /**
     * Returns a list of Conferences that the user created.
     * In order to receive the websafeConferenceKey via the JSON params, uses a POST method.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @return a list of Conferences that the user created.
     * @throws UnauthorizedException when the user is not signed in.
     */
    @ApiMethod(
            name = "getConferencesCreated",
            path = "getConferencesCreated",
            httpMethod = HttpMethod.POST
    )
    public List<Conference> getConferencesCreated(final User user) throws UnauthorizedException {
        // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        String userId = getUserId(user);
        return ofy().load().type(Conference.class)
                .ancestor(Key.create(Profile.class, userId))
                .order("name").list();
    }

    /**
     * Registers to the specified Conference.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeConferenceKey The String representation of the Conference Key.
     * @return Boolean true when success, otherwise false
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "registerForConference",
            path = "conference/{websafeConferenceKey}/registration",
            httpMethod = HttpMethod.POST
    )
    public WrappedBoolean registerForConference(final User user,
                                         @Named("websafeConferenceKey")
                                         final String websafeConferenceKey)
        throws UnauthorizedException, NotFoundException, ForbiddenException, ConflictException {
        // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        final String userId = getUserId(user);
        TxResult<Boolean> result = ofy().transact(new Work<TxResult<Boolean>>() {
            @Override
            public TxResult<Boolean> run() {
                Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
                Conference conference = ofy().load().key(conferenceKey).now();
                // 404 when there is no Conference with the given conferenceId.
                if (conference == null) {
                    return new TxResult<>(new NotFoundException(
                            "No Conference found with key: " + websafeConferenceKey));
                }
                // Registration happens here.
                Profile profile = getProfileFromUser(user, userId);
                if (profile.getConferenceKeysToAttend().contains(websafeConferenceKey)) {
                    return new TxResult<>(new ConflictException("You have already registered for this conference"));
                } else if (conference.getSeatsAvailable() <= 0) {
                    return new TxResult<>(new ConflictException("There are no seats available."));
                } else {
                    profile.addToConferenceKeysToAttend(websafeConferenceKey);
                    conference.bookSeats(1);
                    ofy().save().entities(profile, conference).now();
                    return new TxResult<>(true);
                }
            }
        });
        // NotFoundException is actually thrown here.
        return new WrappedBoolean(result.getResult());
    }

    /**
     * Unregister from the specified Conference.
     *
     * @param user An user who invokes this method, null when the user is not signed in.
     * @param websafeConferenceKey The String representation of the Conference Key to unregister
     *                             from.
     * @return Boolean true when success, otherwise false.
     * @throws UnauthorizedException when the user is not signed in.
     * @throws NotFoundException when there is no Conference with the given conferenceId.
     */
    @ApiMethod(
            name = "unregisterFromConference",
            path = "conference/{websafeConferenceKey}/registration",
            httpMethod = HttpMethod.DELETE
    )
    public WrappedBoolean unregisterFromConference(final User user,
                                            @Named("websafeConferenceKey")
                                            final String websafeConferenceKey)
            throws UnauthorizedException, NotFoundException, ForbiddenException, ConflictException {
        // If not signed in, throw a 401 error.
        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }
        final String userId = getUserId(user);
        TxResult<Boolean> result = ofy().transact(new Work<TxResult<Boolean>>() {
            @Override
            public TxResult<Boolean> run() {
                Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
                Conference conference = ofy().load().key(conferenceKey).now();
                // 404 when there is no Conference with the given conferenceId.
                if (conference == null) {
                    return new TxResult<>(new NotFoundException(
                            "No Conference found with key: " + websafeConferenceKey));
                }
                // Un-registering from the Conference.
                Profile profile = getProfileFromUser(user, userId);
                if (profile.getConferenceKeysToAttend().contains(websafeConferenceKey)) {
                    profile.unregisterFromConference(websafeConferenceKey);
                    conference.giveBackSeats(1);
                    ofy().save().entities(profile, conference).now();
                    return new TxResult<>(true);
                } else {
                    return new TxResult<>(false);
                }
            }
        });
        // NotFoundException is actually thrown here.
        return new WrappedBoolean(result.getResult());
    }

    /**Task 1: Add Sessions to a Conference **/

     @ApiMethod(name = "createSession", path = "conference/{websafeConferenceKey}/session", httpMethod = HttpMethod.POST)
    public Session createSession(final User user,
                                 @Named("websafeConferenceKey") final String websafeConferenceKey,
                                 final SessionForm sessionForm)
            throws UnauthorizedException, OAuthRequestException, ConflictException, NotFoundException, ForbiddenException {

        if (user == null) {
            throw new UnauthorizedException("Authorization required");
        }

        final Key<Conference> conferenceKey = Key.create(websafeConferenceKey);
        final Key<Session> sessionKey = factory().allocateId(conferenceKey, Session.class);

        TxResult<Session> result = ofy().transact(
                new Work<TxResult<Session>>() {

                    @Override
                    public TxResult<Session> run() {

                        Conference conference = ofy().load().now(conferenceKey);


                        if (!conference.getOrganizerUserId().equals(getUserId(user))) {
                            return new TxResult<Session>(new ForbiddenException("Only the conference organizer can add sessions"));
                        }


                        Session session = new Session(conferenceKey,
                                sessionKey.getId(),
                                sessionForm.getSessionName(),
                                sessionForm.getSpeakerProfileKeys(),
                                sessionForm.getStartDate(),
                                sessionForm.getDuration(),
                                sessionForm.getLocation(),
                                sessionForm.getSessionType(),
                                sessionForm.getHighlights(),
                                sessionForm.getStartTime()
                        );



                        Collection<Profile> profiles = ofy().load().keys(session.getSpeakerProfileKeys()).values();


                        if (profiles.size() == 0)
                            return new TxResult<Session>(new NotFoundException("Invalid Profile Keys"));

                        Key<Session> sessionKey = ofy().save().entity(session).now();

                        //Associating Profile with Sessions
                        for (Profile profile : profiles) {
                            profile.addSessionToSpeakKey(sessionKey.getString());
                        }

                        ofy().save().entities(profiles).now();

                        List<Session> conferenceSessions = getConferenceSessions(websafeConferenceKey);
                        for(String speakerKey : sessionForm.getSpeakerProfileKeys()) {
                            featureSpeaker(conferenceSessions, speakerKey);
                        }


                        return new TxResult<Session>(session);
                    }
                }

        );



        return result.getResult();
    }

    /**
     * Count the number of times the speaker is in a session in a given conference.
     *
     * I decided to use linear search here (instead of some fancy indexed query) because
     * the complexity of search for a speaker will be O(sessions*users)
     * and as it is only for ONE conference so the number won't be too large to justify
     * a more complex query ( I don't think a conference has more than 1000 sessions and 1000 speakers)
     *
     * @param conferenceSessions
     * @param websafeSpeakerKey
     * @return1
     */
    private int featureSpeaker(
            List<Session> conferenceSessions, String websafeSpeakerKey) {



        int count = 0;

        List<Session> speakerSessions = new ArrayList<>();

        for(Session session : conferenceSessions) {

            if(session.hasSpeaker(websafeSpeakerKey)) {
                speakerSessions.add(session);
            }
        }

        if(speakerSessions.size() >= 2) {
            Profile speaker = ofy().load().key(Key.<Profile>create(websafeSpeakerKey)).now();
            addFeaturedSpeaker(speaker, speakerSessions);
        }

        return count;
    }


    @ApiMethod(name = "getConferenceSessions",
            path = "conference/{websafeConferenceKey}/session",
            httpMethod = HttpMethod.GET)
    public List<Session> getConferenceSessions( @Named("websafeConferenceKey")
                                            final String websafeConferenceKey) {

        return ofy().load().type(Session.class)
                .ancestor( Key.create(websafeConferenceKey))
                .order("name")
                .list();
    }





    @ApiMethod(name = "getConferenceSessionByType",
            path = "conference/{websafeConferenceKey}/session/by-type",
            httpMethod = HttpMethod.GET)
    public List<Session> getConferenceSessionsByType(
            @Named("websafeConferenceKey") final String websafeConferenceKey,
            @Named("sessionType") Session.SessionType sessionType) {

        return ofy().load().type(Session.class)
                .ancestor(Key.create(websafeConferenceKey))
                .filter("sessionType", sessionType)
                .list();
    }

    @ApiMethod(name = "getSessionsBySpeaker",
            path = "conference/session/by-speaker",
            httpMethod = HttpMethod.GET)
    public Collection<Session> getSessionsBySpeaker(@Named("speakerProfileKey") String speakerProfileKey) {

        Profile p = ofy().load().key(Key.<Profile>create(speakerProfileKey)).now();
        return ofy().load().keys(p.getSessionsToSpeakKeys()).values();
    }


    /**Task 2: Add Sessions to User Wishlist**/
    @ApiMethod(name = "addSessionToWishlist",
            path = "conference/session/{websafeSessionKey}/wishlist",
            httpMethod = HttpMethod.PUT)
    public WrappedBoolean addSessionToWishlist(
            final User user,
            @Named("websafeSessionKey") final String websafeSessionKey
    ) throws NotFoundException, UnauthorizedException {
        Session s = ofy().load().key(Key.<Session>create(websafeSessionKey)).now();

        //Validating if the session actually exists
        if(s == null) {
            throw new NotFoundException("No Session found with the key: " + websafeSessionKey);
        }

        Profile profile = getProfile(user);

        profile.addSessionKeyWishList(websafeSessionKey);

        ofy().save().entities(profile).now();


        return new WrappedBoolean(true);

    }

    @ApiMethod(name = "getSessionsInWishlist",
            path = "conference/session/wishlist",
            httpMethod = HttpMethod.GET)
    public Collection<Session> getSessionsInWishlist(User user) throws UnauthorizedException {

        Profile profile = getProfile(user);
        return ofy().load().keys(profile.getSessionKeysWishList()).values();
    }



    //Task 3: Work on indexes and queries
    //Come up with 2 additional queries

    @ApiMethod(name = "getSessionsByDates",
            path = "conference/session/by-dates/{dateFrom}/{dateTo}",
            httpMethod = HttpMethod.GET)
    public Collection<Session> getSessionsByDates(
            @Named("dateFrom") final Date dateFrom,
            @Named("dateTo") final Date dateTo) {

        List<Session> sessions = ofy().load().type(Session.class)
                .filter("startDate >= ", dateFrom)
                .filter("startDate <= ", dateTo)
                .order("startDate").list();

        return sessions;
    }

    /**
     * Conferences in the specified date that last lest less/equal than the given duration
     * @param date
     * @param duration
     * @return
     */

    @ApiMethod(name = "getSessionsByDateAndDuration",
            path = "conference/session/by-date-duration/{date}/{duration}",
            httpMethod = HttpMethod.GET)
    public Collection<Session> getSessionsByDateAndDuration(
            @Named("date") final Date date,
            @Named("duration") final int duration) {

        List<Session> sessions = ofy().load().type(Session.class)
                .filter("startDate = ", date)
                .filter("duration <= ", duration)
                .order("duration").list();

        return sessions;
    }

    /**
     * Let’s say that you don't like workshops and you don't like sessions after 7 pm.
     * How would you handle a query for all non-workshop sessions before 7 pm?
     * What is the problem for implementing this query? What ways to solve it did you think of?

     Problem. I had the StartTime as a String and that was a problem
     */
    @ApiMethod(name = "getSessionsNotOfTypeAndUpToTime",
            path = "conference/session/not-type-time/{notSessionType}/{beforeTime}",
            httpMethod = HttpMethod.GET)
    public Collection<Session> getSessionsNotOfTypeAndUpToTime(
            @Named("notSessionType") final Session.SessionType notSessionType,
            @Named("beforeTime") final String beforeTime) {

        Query<Session> query = ofy().load().type(Session.class);

        ArrayList<Session.SessionType> sessionTypes = new ArrayList<>();
        for(Session.SessionType s : Session.SessionType.values() ) {
            if(!s.equals(notSessionType))
                sessionTypes.add(s);
        }

        return  query
                .filter("sessionType IN ", sessionTypes)
                .filter("startTime < ", Session.toTimeInteger(beforeTime))
                .list();
    }


    private void addFeaturedSpeaker(Profile speaker, List<Session> sessions) {
        MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();

        StringBuilder sb = new StringBuilder();
        sb.append("Featured Speaker: " + speaker.getDisplayName() + " will be in sessions: " );

        for(Session s : sessions) {
            sb.append(s.getName() + "\n");
        }

        memcacheService.put(Constants.FEATURED_SPEAKERS_KEY, sb.toString()     );

    }

    @ApiMethod(
            name = "getFeaturedSpeaker",
            path = "featured-speaker",
            httpMethod = HttpMethod.GET
    )
    public Announcement getFeaturedSpeaker() {
        MemcacheService memcacheService = MemcacheServiceFactory.getMemcacheService();
        Object message = memcacheService.get(Constants.FEATURED_SPEAKERS_KEY);
        if (message != null) {
            return new Announcement(message.toString());
        }
        return null;
    }




}
