package org.ovirt.engine.core.bll;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.BackendService;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogableBase;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.utils.EngineLocalConfig;
import org.ovirt.engine.core.utils.crypt.EngineEncryptionUtils;
import org.ovirt.engine.core.utils.timer.OnTimerMethodAnnotation;
import org.ovirt.engine.core.utils.timer.SchedulerUtilQuartzImpl;
import org.ovirt.engine.core.vdsbroker.ResourceManager;
import org.ovirt.engine.core.vdsbroker.VdsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class CertificationValidityChecker implements BackendService {

    private static Logger log = LoggerFactory.getLogger(CertificationValidityChecker.class);

    @Inject
    private SchedulerUtilQuartzImpl scheduler;

    @Inject
    private AuditLogDirector auditLogDirector;

    @Inject
    private VdsDao hostDao;

    @Inject
    private ResourceManager resourceManager;

    @PostConstruct
    public void scheduleJob() {
        scheduler.scheduleAFixedDelayJob(
                this,
                "checkCertificationValidity",
                new Class[0],
                new Object[0],
                10,
                TimeUnit.HOURS.toMinutes(Config.<Integer> getValue(ConfigValues.CertificationValidityCheckTimeInHours)),
                TimeUnit.MINUTES);
    }

    @OnTimerMethodAnnotation("checkCertificationValidity")
    public void checkCertificationValidity() {
        try {
            if (!checkCertificate(EngineEncryptionUtils.getCertificate(EngineLocalConfig.getInstance().getPKICACert()),
                    AuditLogType.ENGINE_CA_CERTIFICATION_HAS_EXPIRED,
                    AuditLogType.ENGINE_CA_CERTIFICATION_IS_ABOUT_TO_EXPIRE,
                    null)
                    || !checkCertificate((X509Certificate) EngineEncryptionUtils.getCertificate(),
                    AuditLogType.ENGINE_CERTIFICATION_HAS_EXPIRED,
                    AuditLogType.ENGINE_CERTIFICATION_IS_ABOUT_TO_EXPIRE,
                    null)) {
                return;
            }

            if (!Config.<Boolean>getValue(ConfigValues.EncryptHostCommunication)) {
                return;
            }

            for (VDS host : hostDao.getAll()) {
                if (host.getStatus() == VDSStatus.Up || host.getStatus() == VDSStatus.NonOperational) {
                    VdsManager hostManager = resourceManager.GetVdsManager(host.getId());
                    List<Certificate> peerCertificates = hostManager.getVdsProxy().getPeerCertificates();

                    if (peerCertificates == null || peerCertificates.isEmpty()) {
                        log.error("Failed to retrieve peer certifications for host '{}'", host.getName());
                    } else {
                        checkCertificate((X509Certificate) peerCertificates.get(0),
                                AuditLogType.HOST_CERTIFICATION_HAS_EXPIRED,
                                AuditLogType.HOST_CERTIFICATION_IS_ABOUT_TO_EXPIRE,
                                host);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to check certification validity: {}", e.getMessage());
            log.error("Exception", e);
        }
    }

    private boolean checkCertificate(X509Certificate cert,
            AuditLogType warnEventType,
            AuditLogType alertEventType,
            VDS host) {
        Calendar certWarnTime = Calendar.getInstance();
        certWarnTime.add(Calendar.DAY_OF_MONTH, Config.<Integer>getValue(ConfigValues.CertExpirationWarnPeriodInDays));

        Calendar certAlertTime = Calendar.getInstance();
        certAlertTime.add(Calendar.DAY_OF_MONTH,
                Config.<Integer> getValue(ConfigValues.CertExpirationAlertPeriodInDays));

        Date expirationDate = cert.getNotAfter();
        AuditLogType eventType = null;

        if (expirationDate.compareTo(certAlertTime.getTime()) < 0) {
            eventType = alertEventType;
        } else if (expirationDate.compareTo(certWarnTime.getTime()) < 0) {
            eventType = warnEventType;
        }

        if (eventType != null) {
            AuditLogableBase event = new AuditLogableBase();
            event.addCustomValue("ExpirationDate", new SimpleDateFormat("yyyy-MM-dd").format(expirationDate));
            event.setVds(host);
            auditLogDirector.log(event, eventType);
            return false;
        }

        return true;
    }
}
