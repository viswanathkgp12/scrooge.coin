import java.util.ArrayList;
import java.security.PublicKey;

public class MaxFeeTxHandler {

  protected UTXOPool utxoPool;

  public MaxFeeTxHandler(UTXOPool utxoPool) {
    this.utxoPool = utxoPool;
  }

  public boolean isValidTx(Transaction tx, UTXOPool utxoPool) {
    ArrayList<UTXO> seenUTXOs = new ArrayList<>();

    ArrayList<Transaction.Input> txInputs = tx.getInputs();
    double inputUtxoAmountTotal = 0.0;

    for (int i = 0; i < txInputs.size(); i++) {
      Transaction.Input input = txInputs.get(i);
      byte[] inputTxHash = input.prevTxHash;
      UTXO utxo = new UTXO(inputTxHash, input.outputIndex);

      boolean isPresentInUtxoPool = utxoPool.contains(utxo);
      if (!isPresentInUtxoPool) {
        System.out.println("UTXO invalid as it is not present in pool");
        return false;
      }

      if (seenUTXOs.contains(utxo)) {
        System.out.println("UTXO already used for one of the outputs");
        System.out.println("returning hence ...");
        return false;
      }

      // Add txHash to the current set of txHashes
      seenUTXOs.add(utxo);

      boolean isValidSignature = this.validateUtxoSignature(tx, i, utxoPool);
      if (!isValidSignature) {
        System.out.println("Not a valid signature on the current input");
        System.out.println("Transaction invalidated");
        return false;
      }

      double prevTxAmount = utxoPool.getTxOutput(utxo).value;
      inputUtxoAmountTotal += prevTxAmount;
    }

    ArrayList<Transaction.Output> txOutputs = tx.getOutputs();
    double outputAmountTotal = 0.0;

    for (Transaction.Output txOutput : txOutputs) {
      if (txOutput.value < 0.0) {
        System.out.println("Output amount cannot be negative");
        return false;
      }

      outputAmountTotal += txOutput.value;
    }

    if (inputUtxoAmountTotal <= 0 || inputUtxoAmountTotal < outputAmountTotal) {
      System.out.println("Sum of total outputs must always be less than total inputs");
      return false;
    }

    return true;
  }

  /** Validate signature on a transaction input */
  private boolean validateUtxoSignature(Transaction tx, int index, UTXOPool utxoPool) {
    Transaction.Input input = tx.getInput(index);
    if (input.signature == null) {
      return false;
    }

    UTXO utxo = new UTXO(input.prevTxHash, input.outputIndex);
    byte[] rawData = tx.getRawDataToSign(index);
    Transaction.Output prevTxOutput = utxoPool.getTxOutput(utxo);
    PublicKey inputPubKey = prevTxOutput.address;

    return Crypto.verifySignature(inputPubKey, rawData, input.signature);
  }

  public Transaction[] handleTxs(Transaction[] possibleTxs) {
    ArrayList<TxWithFees> validTxList = new ArrayList<>();
    ArrayList<UTXO> spentUtxos = new ArrayList<>();

    UTXOPool currentLedger = new UTXOPool(this.utxoPool);
    double currTotalFees = 0;

    boolean newUtxoAdded;

    do {
      newUtxoAdded = false;
      for (Transaction tx : possibleTxs) {

        boolean isValid = this.isValidTx(tx, currentLedger);
        if (!isValid) {
          System.out.println("Not a valid transaction");
          System.out.println("Skipping to the next iteration hence ...");

          continue;
        }

        boolean doubleSpent = this.isAlreadySpent(spentUtxos, tx);
        if (doubleSpent) {
          System.out.println("Transaction already spent");

          ArrayList<TxWithFees> txListWithoutConflicts = new ArrayList<>(validTxList);
          this.removeConflictingTxs(tx, txListWithoutConflicts, spentUtxos);

          UTXOPool tempPool = this.recalculateUTXOPool(txListWithoutConflicts);

          // Add the earlier conflicting tx
          if (this.isValidTx(tx, tempPool)) {
            TxWithFees txWithFees = new TxWithFees(tx, currentLedger);
            txListWithoutConflicts.add(txWithFees);

            double newTotalFees = this.calculateFeesOfSelectedTxs(txListWithoutConflicts);

            if (newTotalFees > currTotalFees) {
              validTxList = txListWithoutConflicts;
              currTotalFees = newTotalFees;
              currentLedger = tempPool;
            }

            continue;
          }
        }

        // Okay - valid transaction
        TxWithFees txWithFees = new TxWithFees(tx, currentLedger);
        validTxList.add(txWithFees);

        // Update spent transaction hashes
        ArrayList<Transaction.Input> txInputs = tx.getInputs();
        txInputs.forEach(txInput -> {
          UTXO utxo = new UTXO(txInput.prevTxHash, txInput.outputIndex);
          spentUtxos.add(utxo);
        });

        // Update fees
        currTotalFees += txWithFees.fees;

        // Clear UTXOs from pool
        this.clearSpentTxsFromMempool(tx, currentLedger);

        // Add new unspent txs to mempool
        this.addNewlyCreatedUnspentTxsToMempool(tx, currentLedger);
        newUtxoAdded = true;
      }
    } while (newUtxoAdded);

    return validTxList.stream().map(txWithFees -> txWithFees.tx).toArray(Transaction[]::new);

  }

  private ArrayList<TxWithFees> removeConflictingTxs(Transaction tx, ArrayList<TxWithFees> selectedTxs,
      ArrayList<UTXO> spentUtxos) {

    for (Transaction.Input input : tx.getInputs()) {
      UTXO currInputUTXO = new UTXO(input.prevTxHash, input.outputIndex);

      for (int idx = 0; idx < selectedTxs.size(); idx++) {
        Transaction cur = selectedTxs.get(idx).tx;
        for (Transaction.Input prevTxInput : cur.getInputs()) {
          UTXO prevSelectedUTXO = new UTXO(prevTxInput.prevTxHash, prevTxInput.outputIndex);

          // Check if is conflicting
          if (currInputUTXO.compareTo(prevSelectedUTXO) == 0) {
            selectedTxs.remove(idx);
            spentUtxos.remove(currInputUTXO);
            break;
          }
        }
      }
    }

    return selectedTxs;
  }

  private UTXOPool recalculateUTXOPool(ArrayList<TxWithFees> selectedTxs) {
    UTXOPool currentLedger = new UTXOPool(this.utxoPool);
    selectedTxs.forEach(txWithFees -> {
      this.clearSpentTxsFromMempool(txWithFees.tx, utxoPool);
      this.addNewlyCreatedUnspentTxsToMempool(txWithFees.tx, utxoPool);
    });
    return currentLedger;
  }

  private double calculateFeesOfSelectedTxs(ArrayList<TxWithFees> selectedTxs) {
    double tempFeesOfCurrPool = 0;
    for (TxWithFees txWithFee : selectedTxs) {
      tempFeesOfCurrPool += txWithFee.fees;
    }

    return tempFeesOfCurrPool;
  }

  /** Check for double spent outputs */
  protected boolean isAlreadySpent(ArrayList<UTXO> spentUTXOs, Transaction tx) {
    ArrayList<Transaction.Input> txInputs = tx.getInputs();
    for (Transaction.Input txInput : txInputs) {
      UTXO utxo = new UTXO(txInput.prevTxHash, txInput.outputIndex);
      if (spentUTXOs.contains(utxo)) {
        System.out.println("Transaction Input already spent");
        return true;
      }
    }

    return false;
  }

  /**
   * Clear utxos of transaction from memory pool
   * 
   * @param tx - Transaction object
   */
  protected void clearSpentTxsFromMempool(Transaction tx, UTXOPool utxoPool) {
    ArrayList<Transaction.Input> txInputs = tx.getInputs();

    for (Transaction.Input input : txInputs) {
      UTXO utxo = new UTXO(input.prevTxHash, input.outputIndex);
      utxoPool.removeUTXO(utxo);
    }
  }

  /**
   * Add new utxos to mempool
   * 
   * @param tx - Transaction object
   */
  protected void addNewlyCreatedUnspentTxsToMempool(Transaction tx, UTXOPool utxoPool) {
    ArrayList<Transaction.Output> txOutputs = tx.getOutputs();

    for (int i = 0; i < txOutputs.size(); i++) {
      UTXO utxo = new UTXO(tx.getHash(), i);
      utxoPool.addUTXO(utxo, txOutputs.get(i));
    }
  }
}

/**
 * Calculate transaction fees pertaining to UTXOPool
 */
class TxWithFees {
  public double fees = 0;
  public Transaction tx;
  private UTXOPool utxoPool;

  public TxWithFees(Transaction tx, UTXOPool utxoPool) {
    this.utxoPool = utxoPool;
    this.tx = tx;
    this.fees = calculateFees(tx);
  }

  private double calculateFees(Transaction tx) {
    return this.sumAllInputs(tx) - this.sumAllOutputs(tx);
  }

  private double sumAllInputs(Transaction tx) {
    double inputAmountTotal = 0;

    ArrayList<Transaction.Input> txInputs = tx.getInputs();

    for (Transaction.Input txInput : txInputs) {
      UTXO utxo = new UTXO(txInput.prevTxHash, txInput.outputIndex);
      double inputTxAmount = this.utxoPool.getTxOutput(utxo).value;
      inputAmountTotal += inputTxAmount;
    }

    return inputAmountTotal;
  }

  private double sumAllOutputs(Transaction tx) {
    double outputAmountTotal = 0;

    ArrayList<Transaction.Output> txOutputs = tx.getOutputs();

    for (Transaction.Output txOutput : txOutputs) {
      outputAmountTotal += txOutput.value;
    }

    return outputAmountTotal;
  }
}