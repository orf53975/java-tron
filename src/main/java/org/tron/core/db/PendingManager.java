package org.tron.core.db;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.JMonitor;
import org.tron.common.utils.JMonitor.Session;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.DupTransactionException;
import org.tron.core.exception.HighFreqException;
import org.tron.core.exception.TaposException;
import org.tron.core.exception.ValidateSignatureException;

@Slf4j
public class PendingManager implements AutoCloseable {

  List<TransactionCapsule> tmpTransactions = new ArrayList<>();
  Manager dbManager;

  public PendingManager(Manager db) {
    Session session = JMonitor.newSession("Exec", "PendingManager");
    try {
      this.dbManager = db;
      tmpTransactions.addAll(db.getPendingTransactions());
      db.getPendingTransactions().clear();
      db.getDialog().reset();
      session.setStatus(session.SUCCESS);
    } finally {
      session.complete();
      JMonitor.countAndDuration("PendingManagerTotalCount", session.getDurationInMillis());
    }
  }

  @Override
  public void close() {
    this.tmpTransactions.stream()
        .filter(
            trx -> dbManager.getTransactionStore().get(trx.getTransactionId().getBytes()) == null)
        .forEach(trx -> {
          try {
            dbManager.pushTransactions(trx);
          } catch (ValidateSignatureException e) {
            logger.debug(e.getMessage(), e);
          } catch (ContractValidateException e) {
            logger.debug(e.getMessage(), e);
          } catch (ContractExeException e) {
            logger.debug(e.getMessage(), e);
          } catch (HighFreqException e) {
            logger.debug(e.getMessage(), e);
          } catch (DupTransactionException e) {
            logger.debug("pending manager: dup trans", e);
          } catch (TaposException e) {
            logger.debug("pending manager: tapos exception", e);
          }
        });
    dbManager.getPoppedTransactions().stream()
        .filter(
            trx -> dbManager.getTransactionStore().get(trx.getTransactionId().getBytes()) == null)
        .forEach(trx -> {
          try {
            dbManager.pushTransactions(trx);
          } catch (ValidateSignatureException e) {
            logger.debug(e.getMessage(), e);
          } catch (ContractValidateException e) {
            logger.debug(e.getMessage(), e);
          } catch (ContractExeException e) {
            logger.debug(e.getMessage(), e);
          } catch (HighFreqException e) {
            logger.debug(e.getMessage(), e);
          } catch (DupTransactionException e) {
            logger.debug("pending manager: dup trans", e);
          } catch (TaposException e) {
            logger.debug("pending manager: tapos exception", e);
          }
        });
    dbManager.getPoppedTransactions().clear();
  }
}
