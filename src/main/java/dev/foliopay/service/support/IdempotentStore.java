package dev.foliopay.service.support;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two concurrency rules every money-moving record follows, in one place:
 *
 * <ul>
 *   <li>find-or-create keyed by a unique database constraint: a parallel twin loses the insert, reads the winner's row and returns it;
 *   <li>save-or-merge with optimistic locking: if another request already moved the record on, keep whichever of the two knows more (a
 *       capture is never replaced by a "still pending"), and save that.
 * </ul>
 *
 * The database, not a lock in this process, is what guarantees "once".
 */
@Component
public class IdempotentStore {
  private static final int MAX_MERGES = 3;
  private final TransactionTemplate tx;

  public IdempotentStore(TransactionTemplate tx) {
    this.tx = tx;
  }

  public <E> E findOrCreate(Supplier<Optional<E>> find, Supplier<E> insert) {
    return find.get().orElseGet(() -> insertOrFind(find, insert));
  }

  /** For a caller that has just looked and found nothing: inserts, and reads the winner's row only if a parallel twin inserted first. */
  public <E> E insertOrFind(Supplier<Optional<E>> find, Supplier<E> insert) {
    try {
      return tx.execute(status -> insert.get());
    } catch (DataIntegrityViolationException twin) {
      return find.get().orElseThrow(() -> twin);
    }
  }

  /**
   * Saves {@code ours}. On a lost optimistic race, reloads the stored state; if ours knows more ({@code oursIsAhead}), carries our result onto
   * the stored row ({@code carry}) and saves again; otherwise returns what is stored.
   */
  public <E> E saveMerging(E ours, UnaryOperator<E> save, Supplier<E> reload, BiPredicate<E, E> oursIsAhead, UnaryOperator<E> carry) {
    E candidate = ours;
    for (int attempt = 0; attempt < MAX_MERGES; attempt++) {
      try {
        return save.apply(candidate);
      } catch (ObjectOptimisticLockingFailureException lostRace) {
        E stored = reload.get();
        if (!oursIsAhead.test(stored, ours)) {
          return stored;
        }
        candidate = carry.apply(stored);
      }
    }
    return reload.get();
  }
}
