package io.bitsquare.trade.actor.Event;

import io.bitsquare.trade.actor.command.SendCoins;

import com.google.bitcoin.core.Coin;
import com.google.bitcoin.core.Sha256Hash;

/**
 * <p>Message to send or confirm sending Bitcoin transaction.</p>
 */
public class InsufficientCoins {

    private final SendCoins sendCoins;

    public InsufficientCoins(SendCoins sendCoins) {
        this.sendCoins = sendCoins;
    }

    public String getAddress() { return sendCoins.getAddress();}

    public Coin getValue() {
        return sendCoins.getValue();
    }

    public SendCoins.Purpose getPurpose() {
       return sendCoins.getPurpose();
    }

    public Sha256Hash getOfferId() {
        return sendCoins.getOfferId();
    }
}
