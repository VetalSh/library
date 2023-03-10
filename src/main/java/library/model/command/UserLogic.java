package library.model.command;

import library.utils.constants.Pages;
import library.exceptions.DaoException;
import library.exceptions.ServiceException;
import library.model.dao.UserDao;
import library.model.dao.factory.DaoFactoryCreator;
import library.model.dao.factory.DaoFactoryImpl;
import library.model.entities.Lang;
import library.model.entities.User;
import library.utils.Captcha;
import library.utils.PasswordUtil;
import library.utils.validation.SafeContext;
import library.utils.validation.SafeRequest;
import library.utils.validation.SafeSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Calendar;
import java.util.List;

import static library.utils.constants.ServletAttributes.*;

/**
 * Class-util, has only static methods by design. All methods must be related to User entity, like find user, etc.
 * All public methods here must comply with {@link Command} signature, as
 * they will be used in CommandContext as lambda-functions and called from Front Controller
 * {@link library.controller.FrontController}
 */
public class UserLogic {
    private static final Logger logger = LogManager.getLogger(UserLogic.class);
    private static final DaoFactoryImpl daoFactory = DaoFactoryCreator.getDefaultFactory().newInstance();
    private static final String ATTR_USER_SEARCH_LINK = "user" + ATTR_SEARCH_LINK;
    private static final String EDIT_PAGE = Pages.REGISTER + "?command=user.edit";
    private static final String ERROR_DESCR = "{}={}";

    /**
     * Made private intentionally, no instance is needed by design
     */
    private UserLogic() {
    }

    private static User authenticateUser(String email, String password) throws DaoException, ServiceException {
        logger.debug("start");
        UserDao userDao = daoFactory.getUserDao();
        User user = userDao.findByEmail(email);

        try {
            if (user == null) {
                logger.trace("user with such email was not found");
                throw new ServiceException("error.incorrect.login");
            }

            String hash = PasswordUtil.genHash(password);
            if (!hash.equals(user.getPassword())) {
                logger.trace("authentication error");
                throw new ServiceException("error.incorrect.login");
            }

            logger.trace("user authenticates successfully: user={}", user);
            if (user.getState() != User.State.VALID) {
                logger.trace("User state is not valid for login: {}", user.getState());
                throw new ServiceException("error.invalid.user.state");
            }

            return user;
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            final String msg = "Password hashing issue";
            logger.error(ERROR_DESCR, msg, e.getMessage());
            throw new ServiceException(msg, e);
        } finally {
            logger.debug("end");
        }
    }

    /**
     * Adds new user to the app. Saves to DB. Preserves user input. Errors are shown in page, if any.
     *
     * @param req user request
     * @return home for all users, except admin. for admin, it's users page.
     */
    public static String add(HttpServletRequest req) {
        logger.debug("start");
        String errorPage = Pages.REGISTER + "?command=user.add";

        HttpSession session = req.getSession();
        try {
            User newUser = getValidNewUserToAdd(req);

            UserDao dao = daoFactory.getUserDao();
            User existed = dao.findByEmail(newUser.getEmail());
            if (existed != null) {
                throw new ServiceException("error.duplicated.user.email");
            }
            dao.create(newUser);
            session.removeAttribute(ATTR_SAVED_USER_INPUT);
            session.setAttribute(ATTR_SUCCESS_MSG, "user.added.successfully");
            return nextPageLogic(session);
        } catch (ServiceException | DaoException e) {
            errorPageLogic(session, e);
            return errorPage;
        } finally {
            logger.debug("end");
        }
    }

    private static User getValidNewUserToAdd(HttpServletRequest req) throws ServiceException {
        User newUser = getValidParams(req);
        req.getSession().setAttribute(ATTR_SAVED_USER_INPUT, newUser);
        if (newUser.getPassword().equals("")) {
            throw new ServiceException("error.password.is.empty");
        }

        try {
//            String salt = PasswordUtil.genSalt();
            String pass = PasswordUtil.genHash(newUser.getPassword());
//            newUser.setSalt(salt);
            newUser.setPassword(pass);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new ServiceException("error.password.generation");
        }

        newUser.setFineLastChecked(Calendar.getInstance());

        SafeSession safeSession = new SafeSession(req.getSession());
        User currentUser = safeSession.get(USER).convert(User.class::cast);
        if (currentUser == null) {
            checkCaptcha(req);
        }

        return newUser;
    }

    private static void checkCaptcha(HttpServletRequest req) throws ServiceException {
        SafeSession safeSession = new SafeSession(req.getSession());
        Captcha captcha = safeSession.get(CAPTCHA).notNull().convert(Captcha.class::cast);

        SafeRequest safeRequest = new SafeRequest(req);
        String userInput = safeRequest.get(CAPTCHA).notEmpty().convert();

        if (!userInput.equals(captcha.getToken())) {
            throw new ServiceException("error.in.captcha");
        }
    }

    /**
     * Logs user in. Check provided login and password against specified in DB. Shows errors if any
     *
     * @param req user request
     * @return home page
     */
    public static String login(HttpServletRequest req) {
        logger.debug("start");
        final String errorPage = Pages.LOGIN;

        SafeRequest safeReq = new SafeRequest(req);
        int loginTriesNumber = getLoginTriesNumber(req);
        try {
            if (loginTriesNumber > 1) {
                checkCaptcha(req);
            }
            String email = safeReq.get("email").notEmpty().asEmail().convert();
            String pass = safeReq.get("password").notEmpty().convert();
            logger.trace("Auth request for email '{}'", email);

            HttpSession session = req.getSession();
            User user = authenticateUser(email, pass);

            session.setAttribute(USER, user);
            session.setAttribute(PREFERRED_USER_LANG, user.getPreferredLang());
            session.removeAttribute(LOGIN_TRIES_NUMBER);
            session.removeAttribute(CAPTCHA);
            logger.trace("Added attributes to session: {}={}, {}={}", USER, user, PREFERRED_USER_LANG,
                    user.getPreferredLang());

            return Pages.HOME;
        } catch (DaoException | ServiceException e) {
            errorPageLogic(req.getSession(), e);
            return errorPage;
        } finally {
            logger.debug("end");
        }
    }

    private static int getLoginTriesNumber(HttpServletRequest req) {
        SafeSession safeSession = new SafeSession(req.getSession());

        int loginTriesNumber = Integer.MAX_VALUE;
        try {
            loginTriesNumber = safeSession.get(LOGIN_TRIES_NUMBER).notNull().convert(Integer.class::cast);
            loginTriesNumber++;
        } catch (ServiceException e) {
            loginTriesNumber = 1;
        } finally {
            req.getSession().setAttribute(LOGIN_TRIES_NUMBER, loginTriesNumber);
        }
        return loginTriesNumber;
    }

    private static User getValidParams(HttpServletRequest req) throws ServiceException {
        logger.debug("start");

        SafeRequest safeReq = new SafeRequest(req);
        String pass = safeReq.get(PASS).convert();
        String role = safeReq.get("role").escape().convert();
        String name = safeReq.get("name").escape().convert();
        String state = safeReq.get("state").escape().convert();


        // preserve user input upon error throwing
        User.Builder savedUserInputBuilder = new User.Builder()
                .setName(name)
                .setPassword(pass);
        if (!role.equals("")) {
            try {
                savedUserInputBuilder.setRole(role.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ServiceException("error.wrong.user.role", role);
            }
        }

        if (state.equalsIgnoreCase("valid")) {
            savedUserInputBuilder.setState(User.State.VALID);
        } else {
            savedUserInputBuilder.setState(User.State.BLOCKED);
        }

        User savedUserInput = savedUserInputBuilder.build();

        HttpSession session = req.getSession();
        session.setAttribute(ATTR_SAVED_USER_INPUT, savedUserInput);

        String email = safeReq.get(EMAIL).notEmpty().asEmail().convert();
        savedUserInput.setEmail(email);

        SafeSession safeSession = new SafeSession(session);
        Lang preferredLang;
        try {
            preferredLang = safeSession.get(PREFERRED_USER_LANG).notNull().convert(Lang.class::cast);
        } catch (ServiceException e) {
            preferredLang = (Lang) req.getServletContext().getAttribute(DEFAULT_LANG);
            if (preferredLang == null) {
                throw new ServiceException("error.default.app.language.not.found");
            }
        }
        savedUserInput.setPreferredLang(preferredLang);
        savedUserInput.setModified(Calendar.getInstance());

        logger.trace("email={}, role={}, name={}, preferredLang={}, state={}",
                email, role, name, preferredLang, state);
        session.removeAttribute(ATTR_SAVED_USER_INPUT);

        logger.debug("end");
        return savedUserInput;
    }

    /**
     * Edit user. Allowed to all users roles, except UNKNOWN. Every user can edit oneself, only admin can edit other
     * users
     *
     * @param req user request
     * @return edit page in case of error, home/users page upon success for all users/admin
     * @throws ServiceException in case current user is null (which should never happen)
     */
    public static String edit(HttpServletRequest req) throws ServiceException {
        logger.debug("start");

        try {
            return findUserToEditAndSaveItToSession(req);
        } catch (ServiceException e) {
            logger.trace("id isn't in request, try to proceed with user editing");
            return updateUserSavedInSession(req);
        } finally {
            logger.debug("end");
        }
    }

    private static String updateUserSavedInSession(HttpServletRequest req) throws ServiceException {
        logger.debug("start");
        HttpSession session = req.getSession();

        try {
            User newVersionOfUser = getValidToUpdateUser(req);

            daoFactory.getUserDao().update(newVersionOfUser);

            User currentUser = (User) session.getAttribute(USER);
            if (currentUser.getId() == newVersionOfUser.getId()) {
                session.setAttribute(USER, newVersionOfUser);
            }
        } catch (ServiceException | DaoException e) {
            errorPageLogic(session, e);
            return EDIT_PAGE;
        }

        session.removeAttribute(ATTR_PROCEED_USER);
        logger.debug("end");
        return nextPageLogic(session);
    }

    private static User getValidToUpdateUser(HttpServletRequest req) throws ServiceException, DaoException {
        logger.debug("start");
        HttpSession session = req.getSession();
        SafeSession safeSession = new SafeSession(session);

        User oldVersionOfUser = safeSession.get(ATTR_PROCEED_USER).notNull().convert(User.class::cast);
        User newVersionOfUser = getValidParams(req);

        newVersionOfUser.setId(oldVersionOfUser.getId());
        newVersionOfUser.setFineLastChecked(oldVersionOfUser.getFineLastChecked());
        newVersionOfUser.setFine(oldVersionOfUser.getFine());
        //preserve user input
        session.setAttribute(ATTR_PROCEED_USER, newVersionOfUser);

        if (newVersionOfUser.getPassword().equals("")) {
            newVersionOfUser.setPassword(oldVersionOfUser.getPassword());
        } else {
//            String salt = oldVersionOfUser.getSalt();
            try {
                String pass = PasswordUtil.genHash(newVersionOfUser.getPassword());
                newVersionOfUser.setPassword(pass);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new ServiceException("error.password.generation");
            }
        }

        UserDao dao = daoFactory.getUserDao();
        User existed = dao.findByEmail(newVersionOfUser.getEmail());
        if (existed != null
                && existed.getId() != newVersionOfUser.getId()) {
            throw new ServiceException("error.duplicated.user.email");
        }

        logger.debug("end");
        return newVersionOfUser;
    }

    private static void errorPageLogic(HttpSession session, Exception e) {
        logger.error(e.getMessage());
        session.setAttribute(USER_ERROR, e.getMessage());
        if (e instanceof ServiceException) {
            session.setAttribute(USER_ERROR_PARAMS, ((ServiceException) e).getMsgParameters());
        }
    }

    private static String findUserToEditAndSaveItToSession(HttpServletRequest req) throws ServiceException {
        logger.debug("start");
        HttpSession session = req.getSession();

        long userID = new SafeRequest(req).get("id").notEmpty().convert(Long::parseLong);
        logger.trace("id={}", userID);

        try {
            checkIfUserIsAllowedToEditThis(userID, session);

            UserDao dao = daoFactory.getUserDao();
            User user = dao.read(userID);
            if (user == null) {
                throw new ServiceException("error.user.not.found", String.valueOf(userID));
            }
            session.setAttribute(ATTR_PROCEED_USER, user);
        } catch (DaoException | ServiceException e) {
            errorPageLogic(session, e);
            return EDIT_PAGE;
        } finally {
            logger.debug("end");
        }

        return EDIT_PAGE;
    }

    private static void checkIfUserIsAllowedToEditThis(long userIDToEdit, HttpSession session) throws ServiceException {
        User currentUser = (User) session.getAttribute(USER);
        if (currentUser == null ||
                (currentUser.getId() != userIDToEdit && currentUser.getRole() != User.Role.ADMIN)) {
            logger.error("current user cannot edit user with id {}", userIDToEdit);
            throw new ServiceException("error.resource.forbidden");
        }
    }

    private static String nextPageLogic(HttpSession session) throws ServiceException {
        logger.debug("start");
        SafeSession safeSession = new SafeSession(session);

        String page = new SafeSession(session).get(ATTR_USER_SEARCH_LINK).convert(String.class::cast);
        if (page == null) {
            logger.trace("no previous search link in session");

            User currentUser = safeSession.get(USER).convert(User.class::cast);
            if (currentUser == null) {
                page = Pages.LOGIN;
            } else if (currentUser.getRole() == User.Role.ADMIN) {
                page = Pages.USERS;
            } else {
                page = Pages.HOME;
            }
        }

        logger.debug("end");
        return page;
    }

    /**
     * Logouts user, invalidates it's session. Saves user preferred language to new session.
     *
     * @param req user request
     * @return login page
     * @throws ServiceException in case current user is null (which shouldn't happen)
     */
    public static String logout(HttpServletRequest req) throws ServiceException {
        logger.debug("start");
        HttpSession session = req.getSession();

        Lang lang = new SafeSession(session)
                .get(PREFERRED_USER_LANG)
                .convert(Lang.class::cast);

        session.invalidate();

        if (lang != null) {
            logger.trace("saving user language: lang={}", lang);
            session = req.getSession();
            session.setAttribute(PREFERRED_USER_LANG, lang);
        }

        logger.debug("end");
        return Pages.LOGIN;
    }

    /**
     * Deletes user by id. User cannot be deleted, if it has fine.
     *
     * @param req user request
     * @return last search or users page for admin
     * @throws ServiceException if id isn't provided or user has fine
     * @throws DaoException in case of some DB error
     */
    public static String delete(HttpServletRequest req) throws ServiceException, DaoException {
        logger.debug("start");

        SafeRequest safeReq = new SafeRequest(req);
        long id = safeReq.get("id").notNull().convert(Long::parseLong);

        UserDao dao = daoFactory.getUserDao();
        User toDelete = dao.read(id);
        logger.trace("user={}", toDelete);
        if (toDelete.getFine() > 0) {
            logger.error("Cannot delete user with fine");
            throw new ServiceException("error.cannot.delete.user.with.fine");
        }
        dao.delete(id);

        logger.debug("end");
        return nextPageLogic(req.getSession());
    }

    /**
     * Finds all users by pattern with pagination.
     *
     * @param req user request
     * @return users page
     * @throws ServiceException in case of errors
     */
    public static String find(HttpServletRequest req) throws ServiceException {
        logger.debug("start");
        return CommonLogicFunctions.findWithPagination(req, daoFactory.getUserDao(), ATTR_USERS, "user", Pages.USERS);
    }

    /**
     * Changes current lang for any user: authenticated or not. For authenticated change is saved to DB.
     *
     * @param req user request
     * @return page on which user was before changing language
     * @throws ServiceException in case of uninitialized crucial app parameters
     * @throws DaoException in case of DB errors
     */
    @SuppressWarnings("unchecked")
    public static String setLang(HttpServletRequest req) throws ServiceException, DaoException {
        logger.debug("start");

        SafeRequest safeReq = new SafeRequest(req);
        String requestedLangCode = safeReq.get(PREFERRED_USER_LANG).notEmpty().convert();
        logger.trace("found lang in request: {}", requestedLangCode);

        SafeContext context = new SafeContext(req.getServletContext());
        List<Lang> supported = context.get(SUPPORTED_LANGUAGES).notNull().convert(List.class::cast);

        Lang requestedLang = null;
        for (Lang l : supported) {
            if (l.getCode().equals(requestedLangCode)) {
                requestedLang = l;
            }
        }
        if (requestedLang == null) {
            throw new ServiceException("error.requested.lang.is.not.supported");
        }

        HttpSession session = req.getSession();
        session.setAttribute(PREFERRED_USER_LANG, requestedLang);
        logger.trace("language applied to session: {}", requestedLang);

        User user = (User) session.getAttribute(USER);
        if (user != null) {
            user.setPreferredLang(requestedLang);
            UserDao userDao = daoFactory.getUserDao();
            userDao.update(user);
            logger.trace("language saved for user as preferred: user={}, lang={}", user, requestedLang);
        }

        String url = safeReq.get(LAST_VISITED_PAGE).notEmpty().convert();
        logger.trace("proceed back to url={}", url);
        logger.debug("end");
        return url;
    }
}
