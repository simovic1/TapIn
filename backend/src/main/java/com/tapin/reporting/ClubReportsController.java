package com.tapin.reporting;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1/reports")
class ClubReportsController {
    private final ClubReportsService service;
    ClubReportsController(ClubReportsService service) { this.service=service; }
    @GetMapping("/attendance")
    ClubReportsService.Report report(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) Long membershipId,@RequestParam(required=false) String belt,
        @RequestParam(defaultValue="presentCount,desc") String sort,@RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.report(from,to,membershipId,belt,sort,page,size);
    }
    @GetMapping("/inactive-members")
    ClubReportsService.InactivePage inactive(@RequestParam(defaultValue="14") int days,
        @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.inactive(days,page,size);
    }
    @GetMapping(value="/attendance.csv",produces="text/csv")
    ResponseEntity<byte[]> csv(@RequestParam LocalDate from,@RequestParam LocalDate to,
        @RequestParam(required=false) Long membershipId,@RequestParam(required=false) String belt,
        @RequestParam(defaultValue="presentCount,desc") String sort) {
        var report=service.report(from,to,membershipId,belt,sort,0,Integer.MAX_VALUE);
        StringBuilder csv=new StringBuilder("\uFEFFPrezime;Ime;E-mail;Pojas;Dolasci ukupno;Dolasci u mjesecu;Zadnjih 7 dana;Zadnjih 30 dana;Prijave bez dolaska;Postotak nedolazaka;Zadnji dolazak;Evidentirane prijave;Nepotvrđene prijave\r\n");
        for(var r:report.items()) {
            csv.append(String.join(";",text(r.lastName()),text(r.firstName()),text(r.email()),text(r.currentBelt()),
                ""+r.presentCount(),""+r.presentThisMonth(),""+r.presentLast7Days(),""+r.presentLast30Days(),
                ""+r.registeredButAbsent(),r.noShowRate()==null?"":String.format(Locale.forLanguageTag("hr-HR"),"%.1f",r.noShowRate()*100),
                r.lastPresentOn()==null?"":r.lastPresentOn().format(DateTimeFormatter.ofPattern("dd.MM.yyyy.")),
                ""+r.decidedRegistrations(),""+r.unmarkedRegistrations())).append("\r\n");
        }
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"dolasci_"+from+"_"+to+".csv\"")
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
    static String text(String input) {
        // Quote every text field; prefix formula-like text even when hidden behind whitespace/control characters.
        String significant=input.replaceFirst("^[\\s\\p{Z}\\p{Cc}\\p{Cf}]*", "");
        if(!significant.isEmpty() && "=+-@".indexOf(significant.charAt(0))>=0) input="'"+input;
        return "\""+input.replace("\"","\"\"")+"\"";
    }
}
