import java.security.PublicKey;
import java.util.ArrayList;

public class TxHandler {

    protected UTXOPool utxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent
     * transaction outputs) is {@code utxoPool}. This should make a copy of utxoPool
     * by using the UTXOPool(UTXOPool uPool) constructor.
     */
    public TxHandler(UTXOPool utxoPool) {
        this.utxoPool = utxoPool;
    }

    /**
     * @return true if: (1) all outputs claimed by {@code tx} are in the current
     *         UTXO pool, (2) the signatures on each input of {@code tx} are valid,
     *         (3) no UTXO is claimed multiple times by {@code tx}, (4) all of
     *         {@code tx}s output values are non-negative, and (5) the sum of
     *         {@code tx}s input values is greater than or equal to the sum of its
     *         output values; and false otherwise.
     */
    public boolean isValidTx(Transaction tx) {
        ArrayList<UTXO> seenUTXOs = new ArrayList<>();

        ArrayList<Transaction.Input> txInputs = tx.getInputs();
        double inputUtxoAmountTotal = 0.0;

        for (int i = 0; i < txInputs.size(); i++) {
            Transaction.Input input = txInputs.get(i);
            byte[] inputTxHash = input.prevTxHash;
            UTXO utxo = new UTXO(inputTxHash, input.outputIndex);

            boolean isPresentInUtxoPool = this.utxoPool.contains(utxo);
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

            boolean isValidSignature = this.validateUtxoSignature(tx, i);
            if (!isValidSignature) {
                System.out.println("Not a valid signature on the current input");
                System.out.println("Transaction invalidated");
                return false;
            }

            double prevTxAmount = this.utxoPool.getTxOutput(utxo).value;
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
    private boolean validateUtxoSignature(Transaction tx, int index) {
        Transaction.Input input = tx.getInput(index);
        if (input.signature == null) {
            return false;
        }

        UTXO utxo = new UTXO(input.prevTxHash, input.outputIndex);
        byte[] rawData = tx.getRawDataToSign(index);
        Transaction.Output prevTxOutput = this.utxoPool.getTxOutput(utxo);
        PublicKey inputPubKey = prevTxOutput.address;

        return Crypto.verifySignature(inputPubKey, rawData, input.signature);
    }

    /**
     * Handles each epoch by receiving an unordered array of proposed transactions,
     * checking each transaction for correctness, returning a mutually valid array
     * of accepted transactions, and updating the current UTXO pool as appropriate.
     */
    public Transaction[] handleTxs(Transaction[] possibleTxs) {
        ArrayList<Transaction> validTxList = new ArrayList<>();
        ArrayList<UTXO> spentUtxos = new ArrayList<>();

        boolean newUtxoAdded;

        /**
         * Whenever a new valid transaction is updated in mempool, it gives new UTXOs
         * and hence some of those previously invalid transaction inputs might nowbe
         * valid
         * 
         * Let's say mempool initially has UTXOs 0xabcd
         * 
         * ROUND 1 -------------------------------------------------------
         * TX1 | Inputs | Outputs | Validity
         *     | 0xef12 | 0x3456  | INVALID as 0xef12 is not in UTXO pool
         * TX2 |        |         |
         *     | 0xabcd | 0xef12  | VALID
         * 
         * Only TX2 will be added after Round 1 UTXOPool now has txHashes 0xef12
         * 
         * ROUND 2
         * TX1 | Inputs | Outputs | Validity 
         *     | 0xef12 | 0x3456  | VALID as the newly created output from TX2
         */
        do {
            newUtxoAdded = false;
            for (Transaction tx : possibleTxs) {

                boolean isValid = this.isValidTx(tx);
                if (!isValid) {
                    System.out.println("Not a valid transaction");
                    System.out.println("Skipping to the next iteration hence ...");
                    continue;
                }

                boolean doubleSpent = this.isAlreadySpent(spentUtxos, tx);
                if (doubleSpent) {
                    System.out.println("Transaction already spent");
                    continue;
                }

                // Okay - valid transaction
                validTxList.add(tx);

                // Update spent transaction hashes
                ArrayList<Transaction.Input> txInputs = tx.getInputs();
                txInputs.forEach(txInput -> {
                    UTXO utxo = new UTXO(txInput.prevTxHash, txInput.outputIndex);
                    spentUtxos.add(utxo);
                });

                // Clear UTXOs from pool
                this.clearSpentTxsFromMempool(tx, this.utxoPool);

                // Add new unspent txs to mempool
                this.addNewlyCreatedUnspentTxsToMempool(tx, this.utxoPool);
                newUtxoAdded = true;
            }
        } while (newUtxoAdded);

        return validTxList.toArray(new Transaction[0]);
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
