package dev.foliopay.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("Idempotent store: who creates the record and who wins a race")
class IdempotentStoreTest {
  private final TransactionTemplate tx = mock(TransactionTemplate.class);
  private final IdempotentStore store = new IdempotentStore(tx);

  @SuppressWarnings("unchecked")
  private void transactionRunsCallback() {
    when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
  }

  /** A stand-in for an entity with a state that only moves forward. */
  static final class Item {
    int rank;
    Item(int rank) { this.rank = rank; }
  }

  private static ObjectOptimisticLockingFailureException lost() { return new ObjectOptimisticLockingFailureException("Item", "id"); }

  @Test
  @DisplayName("Returns the existing record without inserting")
  void returnsTheExistingRecordWithoutInserting() {
    AtomicInteger inserts = new AtomicInteger();
    String found = store.findOrCreate(() -> Optional.of("existing"), () -> { inserts.incrementAndGet(); return "new"; });
    assertThat(found).isEqualTo("existing");
    assertThat(inserts).hasValue(0);
  }

  @Test
  @DisplayName("Inserts when nothing exists")
  void insertsWhenNothingExists() {
    transactionRunsCallback();
    assertThat(store.findOrCreate(Optional::empty, () -> "new")).isEqualTo("new");
  }

  @Test
  @DisplayName("A twin that loses the insert returns the winner's row")
  void aTwinThatLosesTheInsertReturnsTheWinnersRow() {
    transactionRunsCallback();
    AtomicInteger lookups = new AtomicInteger();
    String result = store.findOrCreate(() -> lookups.getAndIncrement() == 0 ? Optional.empty() : Optional.of("winner"), () -> { throw new DataIntegrityViolationException("duplicate key"); });
    assertThat(result).isEqualTo("winner");
  }

  @Test
  @DisplayName("A constraint failure with no row to read is not hidden")
  void aConstraintFailureWithNoRowToReadIsNotHidden() {
    transactionRunsCallback();
    assertThatThrownBy(() -> store.findOrCreate(Optional::empty, () -> { throw new DataIntegrityViolationException("other violation"); })).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("A successful save is returned as is")
  void aSuccessfulSaveIsReturnedAsIs() {
    Item ours = new Item(1);
    assertThat(store.saveMerging(ours, i -> i, () -> new Item(0), (stored, mine) -> mine.rank > stored.rank, stored -> stored)).isSameAs(ours);
  }

  @Test
  @DisplayName("A lost race where the stored state knows more returns the stored state")
  void aLostRaceWhereTheStoredStateKnowsMoreReturnsTheStoredState() {
    Item stored = new Item(2); // captured
    Item ours = new Item(0);   // still pending
    Item result = store.saveMerging(ours, i -> { throw lost(); }, () -> stored, (s, mine) -> mine.rank > s.rank, s -> s);
    assertThat(result).isSameAs(stored);
  }

  @Test
  @DisplayName("A lost race where we know more carries our result onto the stored row and saves again")
  void aLostRaceWhereWeKnowMoreCarriesOurResultOntoTheStoredRowAndSavesAgain() {
    Item stored = new Item(0);
    Item ours = new Item(2);
    AtomicInteger saves = new AtomicInteger();
    Item result = store.saveMerging(ours, i -> { if (saves.getAndIncrement() == 0) throw lost(); return i; }, () -> stored, (s, mine) -> mine.rank > s.rank, s -> { s.rank = ours.rank; return s; });
    assertThat(result).isSameAs(stored);
    assertThat(result.rank).as("the capture was not lost").isEqualTo(2);
    assertThat(saves).hasValue(2);
  }

  @Test
  @DisplayName("A race that keeps losing ends with the stored state, not an error")
  void aRaceThatKeepsLosingEndsWithTheStoredStateNotAnError() {
    Item stored = new Item(0);
    Item result = store.saveMerging(new Item(2), i -> { throw lost(); }, () -> stored, (s, mine) -> mine.rank > s.rank, s -> s);
    assertThat(result).isSameAs(stored);
  }
}
