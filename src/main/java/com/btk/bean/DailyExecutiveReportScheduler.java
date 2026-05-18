package com.btk.bean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.ejb.ScheduleExpression;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.Timeout;
import jakarta.ejb.Timer;
import jakarta.ejb.TimerConfig;
import jakarta.ejb.TimerService;
import jakarta.inject.Inject;

import java.io.Serializable;
import java.util.logging.Logger;

@Singleton
@Startup
public class DailyExecutiveReportScheduler implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(DailyExecutiveReportScheduler.class.getName());
    private static final String TIMER_INFO = "BTK_DAILY_EXECUTIVE_REPORT";

    @Inject
    private DailyExecutiveReportService reportService;

    @Resource
    private TimerService timerService;

    @PostConstruct
    public void schedule() {
        refreshSchedule();
    }

    @Timeout
    public void onTimeout(Timer timer) {
        if (timer == null || !TIMER_INFO.equals(String.valueOf(timer.getInfo()))) {
            return;
        }
        reportService.sendDailyReport();
    }

    private void refreshSchedule() {
        cancelExistingTimers();

        if (!reportService.isDailyReportEnabled()) {
            LOGGER.info("Daily executive report scheduler is disabled.");
            return;
        }

        int hour = reportService.getScheduleHour();
        int minute = reportService.getScheduleMinute();

        ScheduleExpression expression = new ScheduleExpression()
                .hour(String.valueOf(hour))
                .minute(String.valueOf(minute))
                .second("0");

        TimerConfig timerConfig = new TimerConfig(TIMER_INFO, false);
        timerService.createCalendarTimer(expression, timerConfig);
        LOGGER.info("Daily executive report scheduled at " + String.format("%02d:%02d", hour, minute));
    }

    private void cancelExistingTimers() {
        for (Timer timer : timerService.getAllTimers()) {
            if (timer != null && TIMER_INFO.equals(String.valueOf(timer.getInfo()))) {
                timer.cancel();
            }
        }
    }
}
