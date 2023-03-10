package library.model.tasks;

import library.exceptions.DaoException;
import library.exceptions.ServiceException;
import library.model.dao.BookingDao;
import library.model.dao.UserDao;
import library.model.dao.factory.DaoFactoryCreator;
import library.model.dao.factory.DaoFactoryImpl;
import library.model.entities.Book;
import library.model.entities.Booking;
import library.model.entities.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.ServletContext;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.List;

/**
 * TimerTask which updates user fine in case of non-return books in time
 */
public class UpdateFineTask extends AbstractPeriodicTask {
    private static final Logger logger = LogManager.getLogger(UpdateFineTask.class);
    static final String INIT_PARAM_FINE_PER_DAY = UpdateFineTask.class.getName() + ".finePerDay";
    public static final String TIMER_TASK_INIT_ERROR = "Required attribute {} was not set: " +
            "this.init(servletContext) was not called";
    private final DaoFactoryImpl daoFactory;
    private volatile double finePerDay = -1;

    /**
     * Normal way to use this class
     */
    public UpdateFineTask() {
        daoFactory = DaoFactoryCreator.getDefaultFactory().newInstance();
    }

    /**
     * For tests, you can instantiate class with your daoFactory
     * @param daoFactory will be used to get DAOs
     */
    public UpdateFineTask(DaoFactoryImpl daoFactory) {
        this.daoFactory = daoFactory;
    }

    @Override
    public void run() {
        logger.info("update initiated");

        if (finePerDay == -1) {
            logger.fatal(TIMER_TASK_INIT_ERROR, INIT_PARAM_FINE_PER_DAY);
            return;
        }

        BookingDao bookingDao = daoFactory.getBookingDao();
        UserDao userDao = daoFactory.getUserDao();

        try {
            for (User user: userDao.getAll()) {
                checkUser(bookingDao, userDao, user);
            }
        } catch (DaoException e) {
            logger.error("Unable to get users list: {}", e.getMessage());
            return;
        }

        logger.info("update finished");
    }

    private void checkUser(BookingDao bookingDao, UserDao userDao, User user) {
        logger.trace("proceed user={}", user);
        double oldFine = user.getFine();
        Calendar now = Calendar.getInstance();

        List<Booking> bookings;
        try {
            bookings = bookingDao.findDeliveredByUserID(user.getId());
        } catch (DaoException e) {
            logger.error("Unable to get booking list for user: {}", e.getMessage());
            return;
        }

        Calendar fineLastChecked = user.getFineLastChecked();
        for (Booking booking: bookings) {
            logger.trace("check booking={}", booking);

            Calendar lastModified =
                    booking.getModified().after(fineLastChecked) ? booking.getModified() : fineLastChecked;
            long pastDays = ChronoUnit.DAYS.between(lastModified.toInstant(), now.toInstant());
            logger.trace("booking {}: {} unchecked days past", booking.getId(), pastDays);

            for (Book book : booking.getBooks()) {
                logger.trace("check book={}", book);
                long keepPeriod = booking.getLocated() == Booking.Place.USER ? book.getKeepPeriod() : 1;
                long fineDays = pastDays - keepPeriod;

                if (fineDays > 0) {
                    logger.trace("fineDays={}", fineDays);
                    double fine = fineDays * finePerDay;
                    user.setFine(user.getFine() + fine);
                    logger.trace("keep period exceed, user fine increased on {}", fine);
                }
            }
        }

        if (user.getFine() != oldFine) {
            user.setModified(now);
            user.setFineLastChecked(now);
            try {
                userDao.update(user);
            } catch (DaoException e) {
                logger.error(e.getMessage());
            }
        }
    }

    @Override
    public void init(ServletContext context) throws ServiceException {
        logger.debug("start");

        String fine = context.getInitParameter(INIT_PARAM_FINE_PER_DAY);
        if (fine == null) {
            throw new ServiceException(INIT_PARAM_FINE_PER_DAY + " is not specified in web.xml");
        }

        try {
            double finePerDayCandidate = Double.parseDouble(fine);
            if (finePerDayCandidate < 0) {
                throw new NumberFormatException("it's not positive " + finePerDayCandidate);
            }
            synchronized (this) {
                finePerDay = finePerDayCandidate;
            }
            logger.info("Fine per day initialized successfully: {}", finePerDayCandidate);
        } catch (NumberFormatException e) {
            throw new ServiceException(INIT_PARAM_FINE_PER_DAY + " should be valid positive double value: " + e.getMessage());
        }
        logger.debug("end");
    }
}
