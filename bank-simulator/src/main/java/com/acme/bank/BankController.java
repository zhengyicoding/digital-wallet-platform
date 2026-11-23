package com.acme.bank;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
class BankController {
    private final BankEngine bank;
    BankController(BankEngine bank) { this.bank=bank; }
    @PostMapping("/bank/v1/transactions")
    ResponseEntity<BankEngine.Transaction> submit(@Valid @RequestBody BankEngine.Submission input) throws InterruptedException {
        var settings=bank.config();
        var result=bank.submit(input,settings);
        if(java.util.concurrent.ThreadLocalRandom.current().nextDouble()<settings.hangRate()) BankEngine.pause(30000);
        return ResponseEntity.status(result.fresh()?202:200).body(result.transaction());
    }
    @GetMapping("/bank/v1/transactions/{reference}") BankEngine.Transaction get(@PathVariable String reference) { return bank.get(reference); }
    @GetMapping("/bank/admin/config") BankEngine.Config config() { return bank.config(); }
    @PutMapping("/bank/admin/config") BankEngine.Config configure(@Valid @RequestBody BankEngine.Config settings) { return bank.configure(settings); }
    @GetMapping("/bank/admin/transactions") List<BankEngine.Transaction> list() { return bank.list(); }
    @PostMapping("/bank/admin/reset") ResponseEntity<Void> reset() { bank.reset(); return ResponseEntity.noContent().build(); }
}
